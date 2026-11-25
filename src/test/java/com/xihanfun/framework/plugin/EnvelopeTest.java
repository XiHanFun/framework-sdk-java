package com.xihanfun.framework.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xihanfun.framework.plugin.v1.PluginProto;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 信封的 {@code dataschema}（{@code invocation.md} §4，ADR-0305）：原样写出与交出，空串是没有。
 */
class EnvelopeTest {
    private static Envelope.Builder envelope() {
        return Envelope.builder("orders", "e1", "urn:test", "orders.created");
    }

    /** 宿主写来的信封：只设 {@code dataschema}。 */
    private static Envelope delivered(String dataSchema) {
        return Wire.envelope(PluginProto.Envelope.newBuilder().setTopic("orders").setId("e1").setSource("urn:test")
                .setType("orders.created").setDataSchema(dataSchema).build());
    }

    @Test
    void theDataSchemaIsCarriedAsIs() {
        String schema = "https://example.com/schemas/order.json";
        assertEquals(schema, Wire.envelope(envelope().dataSchema(schema).build()).getDataSchema());
        assertEquals("", Wire.envelope(envelope().build()).getDataSchema(), "no dataschema is written as an empty text");
        // 不裁剪、不核对：只有空白的值由宿主拒绝那一项
        assertEquals(" urn:x ", Wire.envelope(envelope().dataSchema(" urn:x ").build()).getDataSchema());
        assertEquals(Optional.of(schema), delivered(schema).dataSchema());
        assertEquals(Optional.empty(), delivered("").dataSchema(), "an empty text is no dataschema");
        assertEquals(Optional.of(" "), delivered(" ").dataSchema());
        // 相等比较、散列码与调试输出都算上它
        assertEquals(envelope().dataSchema("urn:a").build(), envelope().dataSchema("urn:a").build());
        assertEquals(envelope().dataSchema("urn:a").build().hashCode(), envelope().dataSchema("urn:a").build().hashCode());
        assertNotEquals(envelope().dataSchema("urn:a").build(), envelope().dataSchema("urn:b").build());
        assertNotEquals(envelope().dataSchema("urn:a").build(), envelope().build());
        assertTrue(envelope().dataSchema("urn:a").build().toString().contains("dataSchema=urn:a"));
        assertEquals("Envelope[topic=orders, id=e1, type=orders.created]", envelope().build().toString());
    }
}
