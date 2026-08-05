package com.internal.tools.pubsubgui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoReactiveAutoConfiguration;

// The MongoDB driver is used directly via MongoClientFactory, so disable Spring
// Boot's default Mongo auto-config which would otherwise create an unused client
// pointed at localhost:27017 (its monitor thread logs "connection refused").
@SpringBootApplication(exclude = {MongoAutoConfiguration.class, MongoReactiveAutoConfiguration.class})
public class PubSubGuiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PubSubGuiApplication.class, args);
    }
}
