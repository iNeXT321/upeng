# ===== 前端多阶段构建：node 构建 → nginx 托管 =====
# 构建阶段先只拷 package.json 装依赖（利用 Docker 层缓存，源码改动不重装依赖）
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json ./
RUN npm install --no-audit --no-fund --legacy-peer-deps
COPY . .
RUN npm run build

# 运行阶段：nginx 托管静态文件 + 反代后端
FROM nginx:1.25-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
