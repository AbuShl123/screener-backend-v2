package dev.abu.screener_backend.ws;

import jakarta.websocket.server.ServerEndpointConfig;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * Replacement for SpringConfigurator that works with Spring Boot's embedded Tomcat.
 *
 * SpringConfigurator looks up the ApplicationContext via ContextLoaderListener, which
 * doesn't exist in embedded Tomcat. This class stores the context in a static field:
 * Spring calls setApplicationContext() on the Spring-managed instance; Tomcat calls
 * getEndpointInstance() on a fresh Tomcat-instantiated instance that reads the same static field.
 */
@Component
public class CustomSpringConfigurator extends ServerEndpointConfig.Configurator
        implements ApplicationContextAware {

    private static volatile ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        CustomSpringConfigurator.context = ctx;
    }

    @Override
    public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
        if (context == null) {
            throw new InstantiationException("Spring ApplicationContext not available — endpoint instantiated before Spring context was ready");
        }
        return context.getBean(endpointClass);
    }
}
