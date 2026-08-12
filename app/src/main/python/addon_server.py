"""
Addon Server - Embedded Python HTTP Server for Stremio Addons
Runs inside Android via Chaquopy on 127.0.0.1:7000

Features:
- Serves dynamic manifest.json for Stremio
- Proxies/scrapes video requests with live Cloudflare headers
- Reads config.json on EVERY request (hot-reload via PythonBridge)
- Lightweight: uses only stdlib (http.server, json, urllib)
"""

import json
import os
import sys
import threading
import traceback
import urllib.request
import urllib.parse
import urllib.error
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn

# Global references
_server = None
_server_thread = None
_config_path = None
_port = 7000


def start_server(config_path, port=7000):
    """Called from Kotlin via Chaquopy to start the HTTP server."""
    global _server, _server_thread, _config_path, _port

    _config_path = config_path
    _port = int(port)

    # Import the Kotlin bridge for hot-reload config access
    from com.addonserver import PythonBridge

    class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
        daemon_threads = True
        allow_reuse_address = True

    class AddonHandler(BaseHTTPRequestHandler):
        """HTTP request handler for Stremio addon protocol."""

        def log_message(self, format, *args):
            """Suppress default stderr logging to save I/O on TV."""
            pass

        def _read_config(self):
            """
            Read config on EVERY request (hot-reload).
            Uses PythonBridge to get the live in-memory config from Kotlin
            which is always up-to-date with Telegram bot updates.
            """
            try:
                config_json = PythonBridge.getConfigJson()
                return json.loads(config_json)
            except Exception:
                # Fallback: read from disk directly
                try:
                    with open(_config_path, 'r') as f:
                        return json.load(f)
                except Exception:
                    return {}

        def _send_json(self, data, status=200):
            """Send JSON response."""
            body = json.dumps(data, indent=2).encode('utf-8')
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(body)

        def _send_error_json(self, message, status=400):
            """Send JSON error response."""
            self._send_json({"error": message}, status)

        def do_GET(self):
            """Handle GET requests - Stremio addon protocol."""
            try:
                parsed = urllib.parse.urlparse(self.path)
                path = parsed.path
                query = urllib.parse.parse_qs(parsed.query)

                if path == '/' or path == '/manifest.json':
                    self._handle_manifest()
                elif path.startswith('/catalog/'):
                    self._handle_catalog(path, query)
                elif path.startswith('/stream/'):
                    self._handle_stream(path, query)
                elif path.startswith('/meta/'):
                    self._handle_meta(path, query)
                elif path == '/health':
                    self._send_json({"status": "ok", "port": _port})
                elif path == '/config':
                    # Debug: show current config (for local dev only)
                    config = self._read_config()
                    # Mask cookies for security
                    masked = {}
                    for k, v in config.items():
                        masked[k] = {
                            "cookie": v.get("cookie", "")[:20] + "...",
                            "user_agent": v.get("user_agent", "")
                        }
                    self._send_json(masked)
                else:
                    self._send_error_json("Not found", 404)

            except Exception as e:
                traceback.print_exc()
                self._send_error_json(f"Internal error: {str(e)}", 500)

        def do_POST(self):
            """Handle POST requests."""
            self._send_error_json("POST not supported", 405)

        def do_OPTIONS(self):
            """CORS preflight."""
            self.send_response(200)
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Access-Control-Allow-Methods', 'GET, OPTIONS')
            self.send_header('Access-Control-Allow-Headers', 'Content-Type')
            self.end_headers()

        def _handle_manifest(self):
            """
            Serve Stremio addon manifest.
            This is the entry point that Stremio uses to discover
            what catalogs and resources this addon provides.
            """
            config = self._read_config()
            providers = list(config.keys())

            catalogs = []
            resources = ["catalog", "stream", "meta"]

            for provider_id in providers:
                catalogs.append({
                    "type": "movie",
                    "id": f"{provider_id}_catalog",
                    "name": f"{provider_id.replace('_', ' ').title()} Movies"
                })
                catalogs.append({
                    "type": "series",
                    "id": f"{provider_id}_catalog",
                    "name": f"{provider_id.replace('_', ' ').title()} Series"
                })

            manifest = {
                "id": "community.addon.server",
                "version": "1.0.0",
                "name": "Addon Server",
                "description": "Local Stremio addon host with Cloudflare bypass",
                "resources": resources,
                "types": ["movie", "series"],
                "catalogs": catalogs,
                "idPrefixes": ["tt"],
                "logo": "",
                "background": "",
                "behaviorHints": {
                    "configurable": False,
                    "authenticationRequired": False
                }
            }

            self._send_json(manifest)

        def _handle_catalog(self, path, query):
            """
            Handle catalog requests.
            Returns a list of items for a given catalog type.
            """
            config = self._read_config()
            # Parse: /catalog/{type}/{id}.json
            parts = path.strip('/').split('/')
            # Expected: ['catalog', type, catalog_id]
            if len(parts) < 3:
                self._send_error_json("Invalid catalog path")
                return

            content_type = parts[1]
            catalog_id = parts[2].replace('.json', '')

            # Extract provider from catalog_id (format: provider_X_catalog)
            provider_id = catalog_id.replace('_catalog', '')

            provider_config = config.get(provider_id, {})
            cookie = provider_config.get('cookie', '')
            user_agent = provider_config.get('user_agent', '')

            # For now, return empty catalog with provider info
            # Real implementation would scrape the provider site
            catalog_data = {
                "metas": [],
                "_provider": provider_id,
                "_cookie_health": "ok" if cookie and "PLACEHOLDER" not in cookie else "needs_update"
            }

            self._send_json(catalog_data)

        def _handle_stream(self, path, query):
            """
            Handle stream requests.
            This is where the dynamic header injection happens.
            Every request reads the LIVE config to get the latest
            Cloudflare cookie and User-Agent.
            """
            config = self._read_config()

            # Parse: /stream/{type}/{id}.json
            parts = path.strip('/').split('/')
            if len(parts) < 3:
                self._send_error_json("Invalid stream path")
                return

            content_type = parts[1]
            content_id = parts[2].replace('.json', '')

            # Determine which provider to use
            # Try all providers until one returns results
            streams = []
            for provider_id, provider_config in config.items():
                cookie = provider_config.get('cookie', '')
                user_agent = provider_config.get('user_agent', '')

                if not cookie or cookie == "cf_clearance=PLACEHOLDER":
                    continue  # Skip providers without valid cookies

                # Build request headers with live config
                headers = {
                    'Cookie': cookie,
                    'User-Agent': user_agent,
                    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                    'Accept-Language': 'en-US,en;q=0.5',
                    'Referer': '',
                }

                # Attempt to fetch stream links from provider
                # This is a placeholder - real implementation would scrape
                try:
                    provider_streams = self._fetch_provider_streams(
                        provider_id, content_id, content_type, headers
                    )
                    streams.extend(provider_streams)
                except Exception as e:
                    # Don't fail the whole request if one provider errors
                    pass

            self._send_json({"streams": streams})

        def _handle_meta(self, path, query):
            """Handle meta requests."""
            parts = path.strip('/').split('/')
            if len(parts) < 3:
                self._send_error_json("Invalid meta path")
                return

            content_type = parts[1]
            content_id = parts[2].replace('.json', '')

            meta = {
                "meta": {
                    "id": content_id,
                    "type": content_type,
                    "name": content_id,
                    "poster": "",
                }
            }
            self._send_json(meta)

        def _fetch_provider_streams(self, provider_id, content_id, content_type, headers):
            """
            Fetch streams from a specific provider.
            This is the scraping engine that uses Cloudflare cookies.

            In a real implementation, this would:
            1. Build the provider's search URL
            2. Make an HTTP request with the Cloudflare cookie headers
            3. Parse the response to extract stream URLs
            4. Return Stremio-format stream objects

            For now, returns empty to demonstrate the architecture.
            """
            # Placeholder for scraping logic
            # The headers dict contains the live Cloudflare cookie + UA
            # from ConfigManager (updated via Telegram bot)
            streams = []

            # Example scraping flow (commented out):
            # url = f"https://{provider_id}.com/search?q={content_id}"
            # req = urllib.request.Request(url, headers=headers)
            # with urllib.request.urlopen(req, timeout=10) as resp:
            #     html = resp.read().decode('utf-8')
            #     # Parse html to find stream URLs...
            #     streams.append({
            #         "name": f"{provider_id}",
            #         "title": f"Stream from {provider_id}",
            #         "url": found_url,
            #         "behaviorHints": {
            #             "notWebReady": True,
            #             "proxyHeaders": {
            #                 "request": headers
            #             }
            #         }
            #     })

            return streams

    # Start the server in a background thread
    try:
        _server = ThreadedHTTPServer(('127.0.0.1', _port), AddonHandler)
        _server_thread = threading.Thread(target=_server.serve_forever, daemon=True)
        _server_thread.start()
        sys.stdout.write(f"[AddonServer] Listening on 127.0.0.1:{_port}\n")
        sys.stdout.flush()
    except Exception as e:
        sys.stderr.write(f"[AddonServer] Failed to start: {e}\n")
        sys.stderr.flush()
        raise


def stop_server():
    """Called from Kotlin to gracefully stop the HTTP server."""
    global _server
    if _server:
        _server.shutdown()
        _server = None
    sys.stdout.write("[AddonServer] Stopped\n")
    sys.stdout.flush()


def get_server_status():
    """Return whether the server is running."""
    return _server is not None
