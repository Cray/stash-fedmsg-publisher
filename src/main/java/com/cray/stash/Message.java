package com.cray.stash;

import org.fedoraproject.fedmsg.FedmsgConnection;
import org.fedoraproject.fedmsg.FedmsgMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Message {

    private HashMap<String, Object> content;
    private String topic;
    private static final Logger LOGGER = LoggerFactory.getLogger(Message.class);

    public Message(Map<String, Object> content, String topic) {
        this.topic = topic;
        this.content = (HashMap)content;
    }

    public Map<String, Object> getMessage() {return content;}

    public String getTopic() {return topic;}

    /*
     * This is a helper method for all events, it simply sends the message to Fedmsg with
     * a specified topic and prepends an topic prefix, environment, and modname.
     */
    public void sendMessage(FedmsgConnection connection) {
        LOGGER.info("Sending fedmsg message...");
        FedmsgMessage msg = new FedmsgMessage(
                content,
                topic.toLowerCase(),
                (new java.util.Date()).getTime() / 1000,
                1);
        try {
            connection.send(msg);
        } catch (IOException e) {
            LOGGER.error("IOException occurred when sending fedmsg message: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Exception occurred when sending fedmsg message: " + e.getMessage());
        }
    }
}
