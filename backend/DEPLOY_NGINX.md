Nginx reverse-proxy guidance and a copy-ready server snippet

This file supplies a recommended nginx configuration when serving the SPA under
`/resender` and proxying `/resender/api` to the backend. It includes a safe
WebAuthn stub (if some deployment layer injects `webauthnInterceptor.js`), a long-
cache assets location, a corrected `/resender/api` proxy block (backend should
own CORS), and the SPA route.

Drop the following into your `server {}` block (adjust upstream names/paths as
needed):

```
    # --- Safe WebAuthn stub (serves a no-op interceptor if injected) ---
    location = /webauthnInterceptor.js {
      default_type application/javascript;
      return 200 "(function(){try{if(typeof navigator!=='undefined'){var c=navigator.credentials||{};if(typeof c.create!=='function')c.create=function(){return Promise.reject(new Error('WebAuthn not supported'));};if(typeof c.get!=='function')c.get=function(){return Promise.reject(new Error('WebAuthn not supported'));};navigator.credentials=c;}if(typeof window!=='undefined'&&typeof window.PublicKeyCredential==='undefined'){window.PublicKeyCredential=function(){};}}catch(e){}})();";
    }

    location = /resender/webauthnInterceptor.js {
      default_type application/javascript;
      return 200 "(function(){try{if(typeof navigator!=='undefined'){var c=navigator.credentials||{};if(typeof c.create!=='function')c.create=function(){return Promise.reject(new Error('WebAuthn not supported'));};if(typeof c.get!=='function')c.get=function(){return Promise.reject(new Error('WebAuthn not supported'));};navigator.credentials=c;}if(typeof window!=='undefined'&&typeof window.PublicKeyCredential==='undefined'){window.PublicKeyCredential=function(){};}}catch(e){}})();";
    }

    location ~* /webauthnInterceptor\.js$ {
      default_type application/javascript;
      return 200 "(function(){try{if(typeof navigator!=='undefined'){var c=navigator.credentials||{};if(typeof c.create!=='function')c.create=function(){return Promise.reject(new Error('WebAuthn not supported'));};if(typeof c.get!=='function')c.get=function(){return Promise.reject(new Error('WebAuthn not supported'));};navigator.credentials=c;}if(typeof window!=='undefined'&&typeof window.PublicKeyCredential==='undefined'){window.PublicKeyCredential=function(){};}}catch(e){}})();";
    }

    # --- Static assets (long cache) ---
    location ^~ /resender/assets/ {
        alias /export/home/dpower/nginx/html/resender/assets/;
        access_log off;
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    # --- API proxy (recommended) ---
    # Notes: let the backend handle CORS so credentialed requests work predictably.
    # This block preserves forwarded headers and will allow the backend to emit
    # cookies with the correct attributes when TLS terminates at nginx.
    location /resender/api/ {
      proxy_pass http://resender;  # adjust upstream name/port as needed
      proxy_http_version 1.1;
      proxy_set_header Connection "";
      proxy_set_header Host $host;
      proxy_set_header X-Real-IP $remote_addr;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
      proxy_set_header X-Forwarded-Proto $scheme;
      proxy_set_header X-Forwarded-Host $host;
      proxy_set_header X-Forwarded-Port $server_port;
      proxy_set_header X-Forwarded-Prefix /resender;

      proxy_connect_timeout 300s;
      proxy_send_timeout 300s;
      proxy_read_timeout 300s;

      # Optional: scope cookie path to /resender if you want cookies to be
      # delivered only for requests under the SPA path.
      # proxy_cookie_path / /resender/;

      # IMPORTANT: Do not add `Access-Control-Allow-Origin "*"` if you expect
      # credentialed (cookie) requests. Use the backend to return the appropriate
      # Access-Control-Allow-Origin (echoing the request origin) and
      # Access-Control-Allow-Credentials: true.
    }

    # --- SPA route ---
    location ^~ /resender/ {
        alias /export/home/dpower/nginx/html/resender/;
        index index.html;
        try_files $uri $uri/ /resender/index.html;
        add_header Content-Security-Policy "default-src 'self'; connect-src 'self' http://usaz15ls088 http://usaz15ls088:8080 http://usaz15ls088:8004 http://localhost:8080 http://localhost:8004 http://127.0.0.1:8080 http://127.0.0.1:8004; img-src 'self' data: blob:; script-src 'self' 'unsafe-hashes'; script-src-elem 'self'; script-src-attr 'unsafe-inline'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' data: https://fonts.gstatic.com" always;
        add_header Cache-Control "no-store, no-cache, must-revalidate, max-age=0" always;
        add_header Pragma "no-cache" always;
    }

    # --- Optional: If you must handle CORS at nginx ---
    # Use this safer variant that mirrors the incoming origin instead of using "*".
    # Only use when you cannot adjust backend CORS configuration.
    #
    # location /resender/api/ {
    #   proxy_pass http://resender;
    #   ...
    #   if ($request_method = 'OPTIONS') {
    #     add_header Access-Control-Allow-Origin $http_origin always;
    #     add_header Access-Control-Allow-Methods "GET, POST, PUT, DELETE, PATCH, OPTIONS" always;
    #     add_header Access-Control-Allow-Headers "Authorization, Content-Type, Accept" always;
    #     add_header Access-Control-Allow-Credentials "true" always;
    #     add_header Access-Control-Max-Age 3600 always;
    #     add_header Content-Length 0;
    #     return 204;
    #   }
    #   add_header Access-Control-Allow-Origin $http_origin always;
    #   add_header Access-Control-Allow-Methods "GET, POST, PUT, DELETE, PATCH, OPTIONS" always;
    #   add_header Access-Control-Allow-Headers "Authorization, Content-Type, Accept" always;
    #   add_header Access-Control-Allow-Credentials "true" always;
    # }

    # Notes:
    # - If TLS terminates at nginx, ensure the backend property `reloader.refresh.cookie-secure`
    #   is `true` so the backend can emit `Secure; SameSite=None` on the refresh cookie.
    # - Confirm in browser DevTools that login responses include:
    #   `Set-Cookie: refresh_token=...; Path=/; HttpOnly; Secure; SameSite=None`.

