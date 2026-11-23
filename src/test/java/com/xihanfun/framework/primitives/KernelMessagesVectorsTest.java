package com.xihanfun.framework.primitives;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.xihanfun.framework.Vectors;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 回放 {@code spec/conformance/vectors/primitives/kernel-messages.json}：SDK 自带的默认英文模板与向量逐字相同，
 * 没有进程默认目录时 {@link Error#message()} 用它们。各模块产生这些错误的码、键与参数在各自的测试里按同一份向量核对。
 */
class KernelMessagesVectorsTest {
    @AfterEach
    void resetMessages() {
        MessageScope.resetDefault();
    }

    @Test
    void defaultTemplatesFollowTheVectors() throws IOException {
        JsonNode messages = Vectors.read("primitives/kernel-messages.json").get("messages");
        // 清单读取的错误（kernel.manifest.*、primitives.manifest.*）也在向量里（ADR-0236）
        assertEquals(messages.size(), KernelMessages.templates().size(), "the SDK carries exactly the shared kernel messages");
        for (JsonNode message : messages) {
            String code = message.get("code").textValue();
            String key = message.get("key").textValue();
            String template = message.get("template").textValue();
            assertTrue(ErrorCode.isValid(code), code);
            assertTrue(MessageTemplate.isValidKey(key), key);
            assertEquals(template, KernelMessages.templates().get(key), key);
            assertEquals(Vectors.strings(message.get("arguments")), MessageTemplate.placeholdersOf(template), key + " arguments");

            Map<String, Object> arguments = new LinkedHashMap<>();
            Vectors.strings(message.get("arguments")).forEach(name -> arguments.put(name, "v-" + name));
            assertEquals(MessageTemplate.format(template, arguments), Error.of(code, key, arguments).message(), key + " message");
        }
        assertEquals("PRIMITIVES_VALIDATION_002", ErrorCode.UNINITIALIZED_RESULT);
    }

    @Test
    void everySharedKeyHasExactlyOneConstant() throws Exception {
        // 键的文本只在 KernelMessages 写一遍：它的公开字符串常量（LOCALE 除外）正好是向量里的全部键，各包的消息键常量引用它们
        Set<String> keys = new TreeSet<>();
        for (JsonNode message : Vectors.read("primitives/kernel-messages.json").get("messages")) {
            keys.add(message.get("key").textValue());
        }
        Set<String> constants = new TreeSet<>();
        for (Field field : KernelMessages.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class && !field.getName().equals("LOCALE")) {
                assertTrue(constants.add((String) field.get(null)), field.getName() + " repeats a key");
            }
        }
        assertEquals(keys, constants, "one constant for each shared key");
        assertEquals(keys, new TreeSet<>(KernelMessages.templates().keySet()));
    }

    @Test
    void aConfiguredCatalogStillWinsOverTheDefaults() {
        MessageScope.setDefault(MessageCatalog.of(Map.of("zh-CN", Map.of("kernel.invocation.cancelled", "调用已取消。"))), "zh-CN");
        assertEquals("调用已取消。", Error.cancelled("KERNEL_CANCELLED_001", "kernel.invocation.cancelled").message());
        assertEquals("The invocation exceeded its deadline.", Error.timeout("KERNEL_TIMEOUT_001", "kernel.invocation.deadline_exceeded").message(),
                "keys the catalog lacks fall back to the built-in English template");
        FrameworkException thrown = new FrameworkException(Error.of("KERNEL_OPERATION_002", "kernel.operation.registry_full", Map.of("capacity", 10000)));
        assertEquals("The operation registry has reached its capacity of 10000.", thrown.getMessage());
        assertEquals("kernel.operation.registry_full", thrown.toError().messageKey().orElseThrow());
        assertEquals("KERNEL_OPERATION_002", thrown.code());
    }
}
