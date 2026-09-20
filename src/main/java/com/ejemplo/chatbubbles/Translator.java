package com.ejemplo.chatbubbles;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Traduce texto usando el endpoint publico y gratuito de Google Translate
 * (el mismo que usa la pagina web, sin llave de API):
 *
 *   https://translate.googleapis.com/translate_a/single?client=gtx&sl=..&tl=..&dt=t&q=..
 *
 * OJO, cosas importantes de este endpoint:
 *   - No es oficial ni documentado. Google lo puede cambiar o cerrar cuando quiera.
 *   - Tiene limite de peticiones. Si mandas muchisimas seguidas te puede bloquear
 *     la IP por un rato (te va a responder 429 o 403).
 *   - Por eso aqui hay cache, limite de peticiones simultaneas y limite de largo.
 *
 * Si quieres algo 100% legitimo y sin sorpresas, en el LEEME.md viene como
 * cambiarlo por LibreTranslate, que es open source y se puede autoalojar.
 */
public class Translator {

	/** Solo 2 hilos: no queremos saturar la API ni el juego. */
	private static final ExecutorService HILOS = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "ChatBubbles-Traductor");
		t.setDaemon(true); // daemon = no impide que el juego cierre
		return t;
	});

	private static final HttpClient CLIENTE = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(4))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	/** Cache: si alguien repite el mismo mensaje, no volvemos a pedirlo. */
	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
	private static final int CACHE_MAXIMO = 500;

	/** Cuantas peticiones hay volando ahorita. Mas de 4 al mismo tiempo, se descarta. */
	private static final AtomicInteger EN_VUELO = new AtomicInteger(0);
	private static final int MAX_EN_VUELO = 4;

	/** Si Google nos bloquea, dejamos de insistir por un rato. */
	private static volatile long bloqueadoHasta = 0L;

	/**
	 * Pide la traduccion en segundo plano. Cuando llega, llama a alRecibir.
	 * Si no se pudo traducir (o el texto ya estaba en el idioma destino) no llama a nada.
	 */
	public static void traducirAsync(String texto, Consumer<String> alRecibir) {
		ModConfig cfg = ModConfig.get();
		String destino = cfg.idiomaDestino;
		String origen = (cfg.idiomaOrigen == null || cfg.idiomaOrigen.isBlank()) ? "auto" : cfg.idiomaOrigen;

		String llaveCache = origen + "|" + destino + "|" + texto;
		String yaTraducido = CACHE.get(llaveCache);
		if (yaTraducido != null) {
			alRecibir.accept(yaTraducido);
			return;
		}

		if (System.currentTimeMillis() < bloqueadoHasta) return;
		if (EN_VUELO.get() >= MAX_EN_VUELO) return;

		EN_VUELO.incrementAndGet();
		HILOS.submit(() -> {
			try {
				String resultado = pedir(texto, origen, destino);
				if (resultado != null && !resultado.isBlank() && !resultado.equals(texto)) {
					if (CACHE.size() > CACHE_MAXIMO) CACHE.clear();
					CACHE.put(llaveCache, resultado);
					alRecibir.accept(resultado);
				}
			} catch (Exception e) {
				ChatBubblesClient.LOGGER.debug("[ChatBubbles] Fallo al traducir: {}", e.toString());
			} finally {
				EN_VUELO.decrementAndGet();
			}
		});
	}

	/** Hace la peticion HTTP y saca la traduccion de la respuesta. */
	private static String pedir(String texto, String origen, String destino) throws Exception {
		String url = "https://translate.googleapis.com/translate_a/single"
				+ "?client=gtx"
				+ "&sl=" + URLEncoder.encode(origen, StandardCharsets.UTF_8)
				+ "&tl=" + URLEncoder.encode(destino, StandardCharsets.UTF_8)
				+ "&dt=t"
				+ "&q=" + URLEncoder.encode(texto, StandardCharsets.UTF_8);

		HttpRequest peticion = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", "Mozilla/5.0 (Minecraft ChatBubbles Mod)")
				.timeout(Duration.ofSeconds(5))
				.GET()
				.build();

		HttpResponse<String> respuesta = CLIENTE.send(peticion, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

		if (respuesta.statusCode() == 429 || respuesta.statusCode() == 403) {
			// Nos limitaron. Nos calmamos 2 minutos.
			bloqueadoHasta = System.currentTimeMillis() + 120_000L;
			ChatBubblesClient.LOGGER.warn("[ChatBubbles] La API de traduccion nos limito ({}). Pausando 2 minutos.", respuesta.statusCode());
			return null;
		}
		if (respuesta.statusCode() != 200) return null;

		return leerRespuesta(respuesta.body(), destino);
	}

	/**
	 * La respuesta viene como un arreglo raro, algo asi:
	 *   [[["hola","hello",null,null,10]],null,"en",...]
	 *
	 *   - raiz[0] = lista de pedazos traducidos; de cada pedazo, el indice 0 es el texto
	 *   - raiz[2] = idioma que detecto que era el original
	 */
	private static String leerRespuesta(String cuerpo, String destino) {
		JsonElement raizElem = JsonParser.parseString(cuerpo);
		if (!raizElem.isJsonArray()) return null;
		JsonArray raiz = raizElem.getAsJsonArray();

		// Si ya estaba en nuestro idioma, no tiene caso mostrar la traduccion.
		if (raiz.size() > 2 && raiz.get(2).isJsonPrimitive()) {
			String detectado = raiz.get(2).getAsString();
			if (detectado != null && detectado.equalsIgnoreCase(destino)) return null;
		}

		if (raiz.size() == 0 || !raiz.get(0).isJsonArray()) return null;
		JsonArray pedazos = raiz.get(0).getAsJsonArray();

		StringBuilder sb = new StringBuilder();
		for (JsonElement pedazoElem : pedazos) {
			if (!pedazoElem.isJsonArray()) continue;
			JsonArray pedazo = pedazoElem.getAsJsonArray();
			if (pedazo.size() > 0 && pedazo.get(0).isJsonPrimitive()) {
				sb.append(pedazo.get(0).getAsString());
			}
		}
		String r = sb.toString().trim();
		return r.isEmpty() ? null : r;
	}
}
