package buildingblocks.shared.utils;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

public final class JsonUtils {

    private static final ObjectMapper OBJECT_MAPPER =
            JsonMapper.builder()
                    .findAndAddModules()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build();

    public static String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JacksonException exception) {
            throw new RuntimeException("Failed to serialize object to JSON", exception);
        }
    }

    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }

}