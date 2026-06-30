# =============================================================================
# Master Reference App - マルチステージ Dockerfile
#   build ステージ: Maven Wrapper で jar を生成
#   runtime ステージ: JRE のみの軽量イメージで実行
# =============================================================================

# ---- build stage ------------------------------------------------------------
FROM eclipse-temurin:17-jdk AS build
WORKDIR /build

# 依存解決を先に行いレイヤキャッシュを効かせる
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline

# ソースをコピーしてビルド
COPY src/ src/
RUN ./mvnw -q -B -DskipTests clean package

# ---- runtime stage ----------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /build/target/master-reference-app-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
