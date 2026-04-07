package com.smarttube.web

import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class SmartTubeWebApplication

fun main(args: Array<String>) {
    runApplication<SmartTubeWebApplication>(*args)
}
