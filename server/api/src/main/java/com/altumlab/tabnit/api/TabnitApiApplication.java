package com.altumlab.tabnit.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TabnitApiApplication {

	public static void main(String[] args) {
		if (AccountCommands.requested(args)) {
			// 계정 관리 명령 (D7 9절): 사용자 만들기, 막힘 풀기, 비밀번호 바꾸기. HTTP 서버 없이 띄워 일 하나를 하고 끝낸다.
			SpringApplication command = new SpringApplication(TabnitApiApplication.class);
			command.setWebApplicationType(WebApplicationType.NONE);
			System.exit(SpringApplication.exit(command.run(args)));
		}
		SpringApplication.run(TabnitApiApplication.class, args);
	}

}
