package com.altumlab.tabnit.api;

/**
 * 올린 파일을 받아들이지 않았다 (D7 5절). 판은 만들어지지 않았고 투입 기록에 거부로 남았다.
 *
 * <p>문구는 사용자에게 그대로 보인다. 이유의 코드와 값은 투입 기록에 남은 것과 같다.
 */
class EditionRejectedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String reasonCode;

	private final String reasonDetail;

	private final boolean tooLarge;

	EditionRejectedException(String reasonCode, String reasonDetail, boolean tooLarge, String message) {
		super(message);
		this.reasonCode = reasonCode;
		this.reasonDetail = reasonDetail;
		this.tooLarge = tooLarge;
	}

	String reasonCode() {
		return this.reasonCode;
	}

	/** 거부한 이유의 값. 없으면 null 이다. */
	String reasonDetail() {
		return this.reasonDetail;
	}

	/** 크기 한도를 넘어 거부한 것인가. 그러면 413 으로 답한다. */
	boolean tooLarge() {
		return this.tooLarge;
	}
}
