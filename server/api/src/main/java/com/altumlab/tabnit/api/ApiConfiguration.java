package com.altumlab.tabnit.api;

import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.UserAccounts;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.resource.PathResourceResolver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.altumlab.tabnit.core.EntityQuery;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.altumlab.tabnit.pack.PackDefinitions;
import com.altumlab.tabnit.rules.ActionDecisions;
import com.altumlab.tabnit.rules.ActionRecords;
import com.altumlab.tabnit.link.EditionRecords;
import org.springframework.util.unit.DataSize;
import com.altumlab.tabnit.core.RelationQuery;
import java.time.Clock;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** api 가 쓰는 것들을 잇는다. 계산과 판단은 각 모듈이 하고, api 는 부르기만 한다. */
@Configuration(proxyBeanMethods = false)
class ApiConfiguration implements WebMvcConfigurer {

	/** 서버의 시계. 현재 시각은 api 층에서만 읽고, 엔진에는 날짜로 넘긴다 (D2 AD-23, AD-26). */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	/**
	 * "오늘"을 정하는 시간대. 설치 설정에서 받고, 적지 않으면 Asia/Seoul 이다.
	 * 서버 운영체제의 시간대에 기대지 않는다 (D2 AD-40).
	 */
	@Bean
	ZoneId installationZone(@Value("${tabnit.time-zone:Asia/Seoul}") String zoneId) {
		return ZoneId.of(zoneId);
	}

	/**
	 * 사용자 계정과 로그인의 판단 (D7 9절). 잇달아 몇 번 틀리면 얼마 동안 막는지는 설치 설정에서 받는다.
	 * 적지 않으면 5번, 15분이다 (D2 AD-265).
	 */
	@Bean
	UserAccounts userAccounts(DataSource dataSource, @Value("${tabnit.login.max-failures:5}") int maxFailures,
			@Value("${tabnit.login.lock-duration:15m}") Duration lockDuration) {
		return new UserAccounts(dataSource, maxFailures, lockDuration);
	}

	/** 사용자를 만드는 명령. 실행 인자로 아이디를 줬을 때만 있다 (D2 AD-268). */
	@Bean
	@ConditionalOnProperty(UserCreationCommand.LOGIN_ID_PROPERTY)
	UserCreationCommand userCreationCommand(UserAccounts accounts, AuditLog audit, Clock clock,
			@Value("${" + UserCreationCommand.LOGIN_ID_PROPERTY + "}") String loginId,
			@Value("${tabnit.create-user.role:}") String role) {
		return new UserCreationCommand(accounts, audit, clock, loginId, role, System::getenv);
	}

	/** 로그인 막힘을 푸는 명령. 실행 인자로 아이디를 줬을 때만 있다 (D2 AD-320). */
	@Bean
	@ConditionalOnProperty(AccountCommands.UNLOCK_USER_PROPERTY)
	UserUnlockCommand userUnlockCommand(UserAccounts accounts, AuditLog audit, Clock clock,
			@Value("${" + AccountCommands.UNLOCK_USER_PROPERTY + "}") String loginId) {
		return new UserUnlockCommand(accounts, audit, clock, loginId);
	}

	/** 비밀번호를 바꾸는 명령. 실행 인자로 아이디를 줬을 때만 있다 (D2 AD-320). */
	@Bean
	@ConditionalOnProperty(AccountCommands.CHANGE_PASSWORD_PROPERTY)
	PasswordChangeCommand passwordChangeCommand(UserAccounts accounts, AuditLog audit, Clock clock,
			@Value("${" + AccountCommands.CHANGE_PASSWORD_PROPERTY + "}") String loginId) {
		return new PasswordChangeCommand(accounts, audit, clock, loginId, System::getenv);
	}

	/** 서버의 Pack 폴더에서 Pack 을 적재한다. 폴더는 설치 설정에서 받는다. 정하지 않으면 적재할 수 없다 (D2 AD-272). */
	@Bean
	PackLoading packLoading(DataSource dataSource, Clock clock, @Value("${tabnit.packs-dir:}") String packsDirectory,
			EditionProcessing processing) {
		return new PackLoading(dataSource, clock, packsDirectory, processing);
	}

	/** 감사 기록의 저장 (common). */
	@Bean
	AuditLog auditLog(DataSource dataSource) {
		return new AuditLog(dataSource);
	}

	/** 요청을 감사 기록에 남긴다 (D7 2절). */
	@Bean
	AuditTrail auditTrail(AuditLog auditLog, Clock clock) {
		return new AuditTrail(auditLog, clock);
	}

	private final ObjectProvider<AuditTrail> auditTrail;

	/** 화면의 파일을 둔 폴더. 설치 설정 tabnit.web-dir. 정하지 않으면 서버는 화면을 내주지 않는다. */
	private final String webDirectory;

	ApiConfiguration(ObjectProvider<AuditTrail> auditTrail, @Value("${tabnit.web-dir:}") String webDirectory) {
		this.auditTrail = auditTrail;
		this.webDirectory = webDirectory;
	}

	/**
	 * 화면의 파일을 서버가 함께 내준다 (D2 AD-277). 화면과 API 가 같은 주소에서 나간다.
	 *
	 * <p>화면의 주소(/changes 같은 것)는 파일이 아니다. 새로 고쳐도 화면이 뜨도록 화면의 첫 파일로 답한다 (D2 AD-257).
	 * API 와 운영 주소는 그렇게 답하지 않는다: 없으면 없다고 답한다. 파일의 이름으로 보이는 주소(마지막 마디에 점이 있다)도
	 * 없으면 없다고 답한다.
	 */
	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		if (this.webDirectory.isBlank()) {
			return;
		}
		String location = Path.of(this.webDirectory).toUri().toString();
		registry.addResourceHandler("/**")
			.addResourceLocations(location.endsWith("/") ? location : location + "/")
			// 찾은 결과를 기억해 두지 않는다. 화면의 주소는 끝없이 지어낼 수 있어, 기억해 두면 끝없이 쌓인다.
			.resourceChain(false)
			.addResolver(new PathResourceResolver() {
				@Override
				protected Resource getResource(String resourcePath, Resource base) throws IOException {
					Resource found = super.getResource(resourcePath, base);
					if (found != null || !isScreenAddress(resourcePath)) {
						return found;
					}
					return super.getResource("index.html", base);
				}
			});
	}

	/** 맨 위 주소(/)는 화면의 첫 파일로 넘긴다. 파일의 경로가 비어 있어 위의 길로는 가지 않는다. */
	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		if (!this.webDirectory.isBlank()) {
			registry.addViewController("/").setViewName("forward:/index.html");
		}
	}

	/** 화면의 주소로 볼 것인가. API 와 운영 주소, 파일의 이름으로 보이는 주소는 아니다. */
	static boolean isScreenAddress(String path) {
		// 대문자로 잘못 적은 것도 화면의 주소로 보지 않는다.
		String lower = path.toLowerCase(Locale.ROOT);
		if (lower.equals("api") || lower.startsWith("api/") || lower.equals("actuator") || lower.startsWith("actuator/")) {
			return false;
		}
		String last = path.substring(path.lastIndexOf('/') + 1);
		return !last.contains(".");
	}

	/** 모든 API 의 요청 앞뒤에 감사 기록을 남긴다. */
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new AuditInterceptor(this.auditTrail.getObject())).addPathPatterns("/api/**");
	}

	@Bean
	RelationQuery relationQuery(DataSource dataSource) {
		return new RelationQuery(dataSource);
	}

	/** 판을 투입하고 그 변동을 조치까지 잇는다 (D2 AD-233). */
	@Bean
	EditionProcessing editionProcessing(DataSource dataSource, Clock clock,
			@Value("${spring.servlet.multipart.max-file-size:20MB}") DataSize maxFileSize) {
		// 파일 크기의 한도는 설정 하나로 정한다. 서버가 요청을 받을 때와 link 가 투입할 때 같은 값을 쓴다 (D7 5절).
		if (maxFileSize.toBytes() <= 0) {
			// "한도 없음"(-1)은 받지 않는다. 판의 원본을 저장소에 담으므로 한도가 반드시 있어야 한다.
			throw new IllegalStateException("spring.servlet.multipart.max-file-size 는 0 보다 커야 합니다: " + maxFileSize
					+ ". 올리는 파일 크기의 한도를 정해 다시 실행하세요 (예: 20MB).");
		}
		return new EditionProcessing(dataSource, clock, maxFileSize.toBytes());
	}

	@Bean
	EditionRecords editionRecords(DataSource dataSource) {
		return new EditionRecords(dataSource);
	}

	@Bean
	ActionRecords actionRecords(DataSource dataSource) {
		return new ActionRecords(dataSource);
	}

	@Bean
	ActionDecisions actionDecisions(DataSource dataSource) {
		return new ActionDecisions(dataSource);
	}

	@Bean
	ActionViews actionViews(EntityViews entityViews) {
		return new ActionViews(entityViews);
	}

	/** 개체를 유형, 키, 표시 이름과 함께 옮긴다 (D2 AD-349). */
	@Bean
	EntityViews entityViews(EntityQuery entityQuery, DataSource dataSource, PackDefinitions packDefinitions) {
		return new EntityViews(entityQuery, new com.altumlab.tabnit.core.AttributeValueQuery(dataSource), packDefinitions);
	}

	@Bean
	PackDefinitions packDefinitions(DataSource dataSource) {
		return new PackDefinitions(dataSource);
	}

	@Bean
	EntityQuery entityQuery(DataSource dataSource) {
		return new EntityQuery(dataSource);
	}

}
