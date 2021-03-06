package com.cray.stash;

import java.io.File;
import java.io.InputStream;
import ch.qos.logback.classic.Logger;
import com.atlassian.stash.server.ApplicationPropertiesService;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import com.atlassian.sal.api.ApplicationProperties;

/**
 * Created by swalter on 7/8/2016.
 */
public class EventLoggerFactory {

    private static final String ROOT = "com.cray.stash.signupmanager";
    private static final org.slf4j.Logger stashRootLogger = LoggerFactory.getLogger("ROOT");

    private LoggerContext context;

    private final String homeDir;

    public EventLoggerFactory(ApplicationPropertiesService appService) {
        homeDir = appService.getHomeDir().getAbsolutePath();
        init();
    }

//    public EventLoggerFactory() {
//        homeDir = new File(".").getAbsolutePath();
//        init();
//    }

    private void init() {
        // Assumes LSF4J is bound to logback
        context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        // store the home dir to use for relative paths
        context.putProperty("stash.home", homeDir);

        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);

        InputStream is;
        is = this.getClass().getClassLoader().getResourceAsStream("logback-test.xml");
        if (is != null) {
            stashRootLogger.info("Using logback-test.xml for logger settings");
        } else {
            stashRootLogger.info("Using logback.xml for logger settings");
            is = this.getClass().getClassLoader().getResourceAsStream("logback.xml");
        }

        try {
            configurator.doConfigure(is);
        } catch (JoranException e) {
            System.err.println("Error configuring logging framework" + e);
        }
    }

    public Logger getLogger() {
        return getLogger(ROOT);
    }

    public Logger getLogger(String name) {
        return context.getLogger(name);
    }

    public Logger getLogger(Class<? extends Object> clazz) {
        String className = clazz.toString();
        if (className.startsWith("class ")) {
            className = className.replaceFirst("class ", "");
        }
        return context.getLogger(className);
    }

    public Logger getLoggerForThis(Object obj) {
        String className = obj.getClass().toString();
        if (className.startsWith("class ")) {
            className = className.replaceFirst("class ", "");
        }
        return context.getLogger(className);
    }
}
