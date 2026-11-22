package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altumlab.tabnit.pack.PackLoadResult;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * C-08 (셋째): 같은 Pack 의 판 처리와 Pack 의 재적재는 한 번에 하나만 한다. 재적재가 판 처리를 기다린다 (D2 AD-331).
 *
 * <p>한 판이 어느 규칙으로 처리됐는지가 분명해야 한다: 판을 처리하는 도중에 그 Pack 의 정의가 바뀌지 않는다.
 * 시험용 Pack 의 사본을 쓴다. 모든 이름과 값은 지어낸 것이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PackLoadWaitsTests {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private Clock clock;

	@Autowired
	private EditionProcessing processing;

	@TempDir
	private Path packs;

	@Test
	void 판을_처리하는_동안_같은_Pack_의_적재는_기다리고_다른_Pack_의_적재는_기다리지_않는다() throws Exception {
		PackCopies.copyInto(this.packs, "wait-same", text -> text);
		PackCopies.copyInto(this.packs, "wait-other", text -> text);
		PackLoading loading = new PackLoading(this.dataSource, this.clock, this.packs.toString(), this.processing);
		ExecutorService pool = Executors.newFixedThreadPool(3);
		CountDownLatch holding = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try {
			// 그 Pack 의 처리 차례를 쥐고 있는다 (판을 처리하는 중인 것과 같다).
			Future<String> turn = pool.submit(() -> this.processing.inTurn("wait-same", () -> {
				holding.countDown();
				await(release);
				return "끝";
			}));
			assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

			Future<PackLoadResult> same = pool.submit(() -> loading.load("wait-same"));
			// 같은 Pack 의 적재는 끝나지 않는다.
			assertThatThrownBy(() -> same.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
			// 그 적재가 기다리는 동안에도 다른 Pack 의 적재는 된다.
			assertThat(loading.load("wait-other").outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
			assertThat(same.isDone()).isFalse();
			// 처리가 끝나면 적재가 이어진다.
			release.countDown();
			assertThat(turn.get(30, TimeUnit.SECONDS)).isEqualTo("끝");
			assertThat(same.get(30, TimeUnit.SECONDS).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
		}
		finally {
			release.countDown();
			pool.shutdownNow();
		}
		// 다른 Pack 의 적재는 그 Pack 의 차례만 기다린다. 잠금이 남지 않았다: 같은 Pack 도 다시 적재되고 처리된다.
		assertThat(loading.load("wait-other").outcome()).isEqualTo(PackLoadResult.Outcome.UNCHANGED);
		assertThat(loading.load("wait-same").outcome()).isEqualTo(PackLoadResult.Outcome.UNCHANGED);
		assertThat(this.processing.processPending("wait-same")).isEmpty();
	}

	@Test
	void 다른_Pack_의_판을_처리하는_동안에는_적재가_기다리지_않는다() throws Exception {
		PackCopies.copyInto(this.packs, "wait-free", text -> text);
		PackLoading loading = new PackLoading(this.dataSource, this.clock, this.packs.toString(), this.processing);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch holding = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try {
			pool.submit(() -> this.processing.inTurn("wait-busy", () -> {
				holding.countDown();
				await(release);
				return "끝";
			}));
			assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

			Future<PackLoadResult> loaded = pool.submit(() -> loading.load("wait-free"));

			assertThat(loaded.get(30, TimeUnit.SECONDS).outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
		}
		finally {
			release.countDown();
			pool.shutdownNow();
		}
	}

	@Test
	void 적재가_거부되어도_차례를_놓아_다음_처리와_적재가_막히지_않는다() throws Exception {
		// 정의가 틀린 Pack: 적재가 거부된다.
		PackCopies.copyInto(this.packs, "wait-bad", text -> text.replace("tabnit:dueInDays", "tabnit:없는항목"));
		PackLoading loading = new PackLoading(this.dataSource, this.clock, this.packs.toString(), this.processing);

		assertThatThrownBy(() -> loading.load("wait-bad")).isInstanceOf(PackLoadRejectedException.class);

		// 고친 것은 적재되고, 그 Pack 의 처리도 돈다.
		PackCopies.copyInto(this.packs, "wait-bad", text -> text);
		assertThat(loading.load("wait-bad").outcome()).isEqualTo(PackLoadResult.Outcome.LOADED);
		assertThat(this.processing.processPending("wait-bad")).isEmpty();
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(60, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
