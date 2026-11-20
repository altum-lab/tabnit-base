package com.altumlab.tabnit.api;

import org.springframework.boot.SpringApplication;

public class TestTabnitApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(TabnitApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
