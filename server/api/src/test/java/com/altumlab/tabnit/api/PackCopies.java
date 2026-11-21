package com.altumlab.tabnit.api;

import com.altumlab.tabnit.pack.PackLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import javax.sql.DataSource;

/** 시험용 Pack 의 사본을 식별자를 바꿔 적재한다. 시험마다 다른 식별자를 써서 서로 섞이지 않게 한다. */
final class PackCopies {

	static final Path SOURCE = Path.of("../../packs/test-mini");

	private PackCopies() {
	}

	/**
	 * @param temp 사본을 둘 임시 폴더
	 * @param change 정의 파일(.ttl)의 내용을 고치는 법. 고치지 않으려면 그대로 돌려준다
	 * @return 사본의 폴더. 자료 파일(data/)도 들어 있다
	 */
	static Path load(DataSource dataSource, Path temp, String packId, UnaryOperator<String> change) throws IOException {
		// 폴더의 이름이 Pack 의 식별자와 같아야 한다.
		Path copy = copyInto(temp.resolve(String.valueOf(System.nanoTime())), packId, change);
		new PackLoader(dataSource).load(copy, Instant.parse("2026-07-01T00:00:00Z"));
		return copy;
	}

	/**
	 * 사본을 만들기만 하고 적재하지 않는다. 이미 있으면 정의 파일을 덮어쓴다.
	 *
	 * @param parent 사본의 폴더를 둘 곳. 그 아래에 Pack 의 식별자와 같은 이름의 폴더가 생긴다
	 */
	static Path copyInto(Path parent, String packId, UnaryOperator<String> change) throws IOException {
		Path copy = parent.resolve(packId);
		try (Stream<Path> files = Files.walk(SOURCE)) {
			for (Path source : files.toList()) {
				Path target = copy.resolve(SOURCE.relativize(source).toString());
				if (Files.isDirectory(source)) {
					Files.createDirectories(target);
				}
				else if (source.toString().endsWith(".ttl")) {
					Files.writeString(target,
							change.apply(Files.readString(source, StandardCharsets.UTF_8)).replace("test-mini", packId),
							StandardCharsets.UTF_8);
				}
				else {
					Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		return copy;
	}
}
