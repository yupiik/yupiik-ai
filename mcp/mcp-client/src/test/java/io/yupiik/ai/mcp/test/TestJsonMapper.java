package io.yupiik.ai.mcp.test;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.json.JsonMapper;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

public class TestJsonMapper implements ParameterResolver {
    @Override
    public boolean supportsParameter(final ParameterContext p, final ExtensionContext e) {
        return p.getParameter().getType() == JsonMapper.class;
    }

    @Override
    public Object resolveParameter(final ParameterContext p, final ExtensionContext e) {
        return e.getStore(ExtensionContext.Namespace.create(TestJsonMapper.class))
                .computeIfAbsent("mapper", key -> new Mapper(), Mapper.class)
                .mapper;
    }

    private static final class Mapper implements AutoCloseable {
        private final RuntimeContainer container = ConfiguringContainer.of().start();
        private final JsonMapper mapper = container.lookup(JsonMapper.class).instance();

        @Override
        public void close() {
            container.close();
        }
    }
}
