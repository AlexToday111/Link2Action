package com.link2action.bot


import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class Link2ActionBotApplication

fun main(args: Array<String>){
    runApplication<Link2ActionBotApplication>(*args)
}