package com.altumlab.tabnit.api;

import org.springframework.boot.SpringApplication;

public class TestTabnitApiApplication {

	public static void main(String[] args) {
		// 시험용 실행: 빈 PostgreSQL 컨테이너를 띄우고 화면 흐름 시험이 볼 지어낸 자료를 넣는다.
		// 서버의 Pack 폴더도 지어낸 것으로 둔다 (D-22 의 화면 시험이 거기서 골라 적재한다). 밖에서 정했으면 그것을 쓴다.
		if (System.getProperty("tabnit.packs-dir") == null) {
			System.setProperty("tabnit.packs-dir", E2eSeedData.preparePacksDirectory().toString());
		}
		SpringApplication.from(TabnitApiApplication::main)
			.with(TestcontainersConfiguration.class, E2eSeedData.class)
			.run(args);
	}

}
