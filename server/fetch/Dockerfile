# 取流二进制（hongguo-fetch）的专用运行镜像
#
# 为什么要单独一个容器：上游 v0.31.45 起这个 Go 二进制改成了**动态链接**（要 glibc 2.34+）。
# 后端镜像基于 node:22-alpine（musl），二进制在里面直接 "not found"；
# 宿主 CentOS 7 只有 glibc 2.17，也跑不起来（version `GLIBC_2.34' not found）。
# 所以拿 bookworm-slim（glibc 2.36）单独跑它，后端通过 HG_FETCH_URL 跨容器调用。
#
# ca-certificates 必须装：bookworm-slim 不带根证书，缺了它 TLS 校验直接失败，
# 表现是 "openhongguo: transport failure: https://api5-normal-sinfonlinea.fqnovel.com/..."，
# 看着像网络不通，其实是证书链没人认。
FROM debian:bookworm-slim

# 境内 VPS 直连 deb.debian.org 经常卡；换阿里云源，匹配不到也不报错。
RUN sed -i 's#http://deb.debian.org#http://mirrors.aliyun.com#g; s#http://security.debian.org#http://mirrors.aliyun.com#g' \
      /etc/apt/sources.list.d/debian.sources 2>/dev/null || true

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 二进制不复制进镜像，由 compose 把 ./backend/fetch 只读挂到 /f，
# 换版本时只要替换宿主机上的文件 + 重启容器，不用重新构建镜像。
WORKDIR /f
