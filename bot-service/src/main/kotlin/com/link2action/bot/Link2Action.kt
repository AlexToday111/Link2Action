package com.link2action.bot


import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class Link2ActionBotApplication

fun main(args: Array<String>){
    runApplication<Link2ActionBotApplication>(*args)
}