FROM eclipse-temurin:21-jre-jammy

RUN groupadd -g 1001 venkat && \
    useradd -u 1001 -g venkat -m -s /bin/bash venkat

WORKDIR /opt/payment-service

COPY target/payment-service-*.jar app.jar

RUN chown -R venkat:venkat /opt/payment-service

USER venkat

EXPOSE 8080
EXPOSE 8081

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]

