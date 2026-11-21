package com.altumlab.tabnit.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TabnitApiApplication {

	public static void main(String[] args) {
		if (UserCreationCommand.requested(args)) {
			// 사용자를 만드는 명령 (D7 9절). HTTP 서버 없이 띄워 계정을 만들고 끝낸다.
			SpringApplication command = new SpringApplication(TabnitApiApplication.class);
			command.setWebApplicationType(WebApplicationType.NONE);
			System.exit(SpringApplication.exit(command.run(args)));
		}
		SpringApplication.run(TabnitApiApplication.class, args);
	}

}
