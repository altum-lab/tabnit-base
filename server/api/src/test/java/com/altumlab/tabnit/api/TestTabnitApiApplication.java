package com.altumlab.tabnit.api;

import org.springframework.boot.SpringApplication;

public class TestTabnitApiApplication {

	public static void main(String[] args) {
		// 시험용 실행: 빈 PostgreSQL 컨테이너를 띄우고 화면 흐름 시험이 볼 지어낸 자료를 넣는다.
		SpringApplication.from(TabnitApiApplication::main)
			.with(TestcontainersConfiguration.class, E2eSeedData.class)
			.run(args);
	}

}
