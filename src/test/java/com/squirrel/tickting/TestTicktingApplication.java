package com.squirrel.tickting;

import org.springframework.boot.SpringApplication;

public class TestTicktingApplication {

	public static void main(String[] args) {
		SpringApplication.from(TicktingApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
