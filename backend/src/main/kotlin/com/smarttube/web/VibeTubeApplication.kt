package com.smarttube.web

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class VibeTubeApplication

fun main(args: Array<String>) {
    runApplication<VibeTubeApplication>(*args)
}
