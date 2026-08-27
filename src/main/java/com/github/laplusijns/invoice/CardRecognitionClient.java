package com.github.laplusijns.invoice;

interface CardRecognitionClient {

	<T> T recognize(String instructions, String prompt, String mimeType, byte[] imageBytes, Class<T> responseType);
}
