# 멀티스테이지: NAS에서 직접 빌드 가능. 느리면 PC에서 bootJar 후 jar만 복사하는 방식으로 전환.
FROM gradle:8.14-jdk21 AS build
ARG MODULE=api
WORKDIR /src
COPY . .
RUN gradle :${MODULE}:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
ARG MODULE=api
COPY --from=build /src/${MODULE}/build/libs/*.jar /app.jar
ENV JAVA_OPTS="-Xmx256m" TZ=Asia/Seoul
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app.jar"]
