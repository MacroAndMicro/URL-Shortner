package com.Url_Shortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class UrlShortenerV3Application {

	public static void main(String[] args) {
		// Matches the Docker image, and PostgreSQL rejects legacy IDs such as Asia/Calcutta that Windows JVMs report.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(UrlShortenerV3Application.class, args);
	}

}
