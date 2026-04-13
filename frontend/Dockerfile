FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html /usr/share/nginx/html/index.html
COPY styles.css /usr/share/nginx/html/styles.css
COPY app.js /usr/share/nginx/html/app.js
COPY hls.min.js /usr/share/nginx/html/hls.min.js
COPY dash.all.min.js /usr/share/nginx/html/dash.all.min.js
COPY manifest.webmanifest /usr/share/nginx/html/manifest.webmanifest
COPY sw.js /usr/share/nginx/html/sw.js
COPY icons /usr/share/nginx/html/icons

EXPOSE 80
