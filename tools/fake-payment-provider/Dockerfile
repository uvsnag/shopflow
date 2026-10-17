FROM node:20-alpine
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev
COPY index.js ./
USER node
EXPOSE 9090
HEALTHCHECK CMD wget -qO- http://localhost:9090/health || exit 1
CMD ["node", "index.js"]
