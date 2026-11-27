package com.altumlab.tabnit.api;

import com.altumlab.tabnit.link.EditionRecords;
import com.altumlab.tabnit.pack.PackChangeRejectedException;
import com.altumlab.tabnit.pack.PackInvalidException;
import com.altumlab.tabnit.pack.PackLoadResult;
import com.altumlab.tabnit.pack.PackLoader;
import com.altumlab.tabnit.pack.PackProblem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;

/**
 * 서버의 Pack 폴더에 있는 Pack 하나를 적재한다 (D2 AD-272, D7 10절).
 *
 * <p>Pack 은 올리지 않는다. 설치할 때 정한 폴더 바로 아래의 폴더를 이름으로 고른다. 읽고 검증하고 저장하는 것은 pack 이
 * 한다. 여기서는 이름이 그 폴더 밖을 가리키지 못하게 하고, pack 의 거부를 응답에 담을 모양으로 바꾼다.
 */
class PackLoading {

	/** 폴더의 이름으로 받는 글자. 경로를 가르는 글자와 점이 들어올 수 없다. Pack 의 식별자의 규칙은 pack 이 본다. */
	private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9-]{1,100}");

	/** 가린 자리에 적는 글자. Pack 의 폴더는 그 안의 이름이 이어지도록 빈 글자로, 그 위의 폴더는 표시로 바꾼다. */
	private static final String HIDDEN_FOLDER = "";

	private static final String HIDDEN_ROOT = "(Pack 폴더)";

	private final PackLoader loader;

	/** 판의 기록. 판이 들어온 입력 자료를 묻는 데 쓴다 (D2 AD-159). */
	private final EditionRecords editions;

	private final Clock clock;

	/** 설치 설정 tabnit.packs-dir. 정하지 않았으면 빈 글자다. */
	private final String packsDirectory;

	/** 적재는 한 번에 하나씩 한다. */
	private final ReentrantLock oneAtATime = new ReentrantLock();

	/** 판 처리. 같은 Pack 의 판 처리와 재적재가 한 번에 하나만 되게 그 차례를 함께 쓴다 (D2 AD-331). */
	private final EditionProcessing processing;

	PackLoading(DataSource dataSource, Clock clock, String packsDirectory, EditionProcessing processing) {
		this.loader = new PackLoader(dataSource);
		this.editions = new EditionRecords(dataSource);
		this.clock = clock;
		this.packsDirectory = packsDirectory == null ? "" : packsDirectory;
		this.processing = java.util.Objects.requireNonNull(processing, "판 처리는 반드시 있어야 합니다");
	}

	/** 폴더의 이름으로 받을 수 있는 글자인가. */
	static boolean isAcceptableName(String packId) {
		return packId != null && SAFE_NAME.matcher(packId).matches();
	}

	/**
	 * @param packId Pack 폴더 바로 아래에 있는 폴더의 이름
	 * @throws ApiProblemException 이름이 틀렸거나 그 폴더가 없을 때
	 * @throws PackLoadRejectedException pack 이 적재를 거부했을 때
	 */
	PackLoadResult load(String packId) {
		if (!isAcceptableName(packId)) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "INVALID_PACK_ID", "packId",
					"Pack 의 이름에 쓸 수 없는 글자가 있거나 너무 깁니다. 영어 소문자, 숫자, 붙임표(-)만 써서 100자 이하로 적어 다시 보내세요.");
		}
		Path folder = folderOf(packId);
		try {
			// 그 Pack 의 판 처리가 끝나기를 기다린 뒤에 적재한다: 판을 처리하는 도중에 정의가 바뀌지 않는다 (D2 AD-331).
			// 그 Pack 의 차례를 먼저 잡고 적재의 차례를 잡는다. 거꾸로 하면 한 Pack 의 판 처리를 기다리는 적재가
			// 다른 Pack 의 적재까지 막는다. 판 처리는 적재의 차례를 잡지 않으므로 서로 기다리는 일이 없다.
			return this.processing.inTurn(packId, () -> {
				this.oneAtATime.lock();
				try {
					// 적재한 때는 차례를 잡은 뒤에 읽는다.
					// 판이 들어온 입력 자료의 매핑만 막는다. 그 Pack 의 판 처리가 끝난 뒤에 묻는다 (D2 AD-142, AD-159).
					return this.loader.load(folder, this.clock.instant(), this.editions.inputSourcesWithEditions(packId));
				}
				finally {
					this.oneAtATime.unlock();
				}
			});
		}
		catch (PackInvalidException e) {
			List<PackProblem> problems = new ArrayList<>();
			for (PackProblem problem : e.problems()) {
				problems.add(new PackProblem(withoutServerPath(problem.file(), folder),
						withoutServerPath(problem.item(), folder), withoutServerPath(problem.message(), folder)));
			}
			throw PackLoadRejectedException.invalid(problems);
		}
		catch (PackChangeRejectedException e) {
			throw PackLoadRejectedException
				.changeRejected(e.reasons().stream().map(reason -> withoutServerPath(reason, folder)).toList());
		}
	}

	/**
	 * 문구에서 서버의 폴더 경로를 가린다 (D7 10절: 응답에 서버의 폴더 경로를 담지 않는다).
	 *
	 * <p>pack 의 문구에 경로가 섞이는 경우가 있다: 상대 주소로 적은 정의는 읽을 때 파일의 자리가 붙고, 읽지 못한 폴더는
	 * 그 경로가 문구에 들어간다. 주소의 꼴(file:)과 경로의 꼴을 모두 가린다. Pack 폴더 안에서의 이름은 남긴다.
	 */
	private static String withoutServerPath(String text, Path folder) {
		Path root = folder.getParent();
		String shown = text;
		// 긴 것부터 가린다: Pack 의 폴더, 그 위의 Pack 폴더.
		for (Path path : new Path[] { folder, root }) {
			String uri = path.toUri().toString();
			String asciiUri = path.toUri().toASCIIString();
			for (String form : new String[] { uri, asciiUri, uri.replaceFirst("/$", ""), asciiUri.replaceFirst("/$", ""),
					path.toString() + path.getFileSystem().getSeparator(), path.toString() }) {
				shown = shown.replace(form, path == folder ? HIDDEN_FOLDER : HIDDEN_ROOT);
			}
		}
		return shown;
	}

	/**
	 * 서버의 Pack 폴더 바로 아래에 놓인, 적재할 수 있는 이름의 폴더 (D2 AD-416). 이름의 글자 코드 순이다.
	 *
	 * <p>폴더 안을 읽지 않는다: Pack 으로 맞는지는 적재할 때 안다. 적재가 받지 않는 이름(영어 소문자, 숫자, 붙임표가
	 * 아닌 글자), 파일, 바로가기는 담지 않는다 (적재도 받지 않는다).
	 *
	 * @return 폴더의 이름. Pack 폴더가 정해져 있지 않거나 그 폴더가 없으면 비어 있다 ({@link #configured()} 로 가린다)
	 */
	List<String> folders() {
		if (!configured()) {
			return List.of();
		}
		try (java.util.stream.Stream<Path> children = Files.list(Path.of(this.packsDirectory).toRealPath())) {
			java.util.TreeSet<String> names = new java.util.TreeSet<>();
			for (Path child : children.toList()) {
				String name = child.getFileName().toString();
				// 바로가기는 따라가지 않는다. 따라간 곳이 Pack 폴더 밖일 수 있다.
				if (isAcceptableName(name) && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
					names.add(name);
				}
			}
			// 받는 이름은 영어 소문자, 숫자, 붙임표뿐이라 글자의 순서가 글자 코드 순이다.
			return List.copyOf(names);
		}
		catch (IOException | InvalidPathException e) {
			// 폴더를 읽지 못했다. 서버 쪽의 문제다. 경로는 기록에만 남긴다.
			throw new IllegalStateException("Pack 폴더를 읽지 못했습니다: " + this.packsDirectory, e);
		}
	}

	/** 서버에 Pack 폴더가 정해져 있고 그 폴더가 있는가. */
	boolean configured() {
		try {
			return !this.packsDirectory.isBlank() && Files.isDirectory(Path.of(this.packsDirectory));
		}
		catch (InvalidPathException e) {
			return false;
		}
	}

	/**
	 * 서버의 Pack 폴더에 있는 그 Pack 의 폴더. 읽기만 하는 쪽이 쓴다 (검증 질문을 화면에서 돌리기, D2 AD-422).
	 *
	 * @return 폴더. Pack 폴더가 정해져 있지 않거나, 이름이 받을 수 없는 글자이거나, 그 폴더가 없으면 비어 있다
	 */
	java.util.Optional<Path> folderIfPresent(String packId) {
		if (!isAcceptableName(packId) || !configured()) {
			return java.util.Optional.empty();
		}
		try {
			return java.util.Optional.of(folderOf(packId));
		}
		catch (ApiProblemException e) {
			return java.util.Optional.empty();
		}
	}

	/** 그 이름의 폴더. Pack 폴더 바로 아래에 실제로 있는 폴더여야 한다. */
	private Path folderOf(String packId) {
		if (this.packsDirectory.isBlank()) {
			throw notConfigured();
		}
		try {
			Path root = Path.of(this.packsDirectory);
			if (!Files.isDirectory(root)) {
				throw notConfigured();
			}
			Path realRoot = root.toRealPath();
			Path folder = realRoot.resolve(packId);
			// 바로가기는 따라가지 않는다. 따라간 곳이 Pack 폴더 밖일 수 있다.
			if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) || !realRoot.equals(folder.toRealPath().getParent())) {
				throw new ApiProblemException(HttpStatus.NOT_FOUND, "PACK_FOLDER_NOT_FOUND", "packId",
						"서버의 Pack 폴더에 그 이름의 폴더가 없습니다. Pack 의 이름을 확인하거나, 서버의 Pack 폴더에 Pack 을 둔 뒤 다시 시도하세요.");
			}
			return folder;
		}
		catch (IOException | InvalidPathException e) {
			// 폴더를 읽지 못했다. 서버 쪽의 문제다. 경로는 기록에만 남긴다.
			throw new IllegalStateException("Pack 폴더를 읽지 못했습니다: " + this.packsDirectory, e);
		}
	}

	private static ApiProblemException notConfigured() {
		return new ApiProblemException(HttpStatus.NOT_FOUND, "PACK_FOLDER_NOT_FOUND", "packId",
				"이 서버에는 Pack 폴더가 정해져 있지 않거나 그 폴더가 없습니다. 설치 설정 tabnit.packs-dir 에 Pack 을 둔 폴더를 적고 서버를 다시 띄운 뒤 시도하세요.");
	}

}
