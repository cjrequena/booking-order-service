package com.cjrequena.command.handler;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

@Log4j2
@SpringBootApplication
@RequiredArgsConstructor
@EnableAsync
@ComponentScan(basePackages = {
  "com.cjrequena.command.handler",  // The main package
  "com.cjrequena.core.eventstore"  // Eventstore
})
public class CommandHandlerMainApplication implements CommandLineRunner {

  public static void main(String... args) {
    SpringApplication.run(CommandHandlerMainApplication.class, args);
  }

  @SneakyThrows
  @Override
  public void run(String... args) {

  }
}
