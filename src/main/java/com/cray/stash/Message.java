package com.cray.stash;

import org.fedoraproject.fedmsg.FedmsgConnection;
import org.fedoraproject.fedmsg.FedmsgMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;

public class Message {

    private HashMap<String, Object> content;
    private String topic;
    private final static Logger log = LoggerFactory.getLogger(Message.class);

    public Message(HashMap<String, Object> content, String topic) {
        this.topic = topic;
        this.content = content;
    }

    public HashMap<String, Object> getMessage() {return content;}

    public String getTopic() {return topic;}

    /*
     * This is a helper method for all events, it simply sends the message to Fedmsg with
     * a specified topic and prepends an topic prefix, environment, and modname.
     */
    public void sendMessage(FedmsgConnection connection) {
        log.info("Sending fedmsg message...");
        FedmsgMessage msg = new FedmsgMessage(
                content,
                topic.toLowerCase(),
                (new java.util.Date()).getTime() / 1000,
                1);
        try {
            connection.send(msg);
        } catch (IOException e) {
            log.error("IOException occurred when sending fedmsg message: " + e.getMessage());
        } catch (Exception e) {
            log.error("Exception occurred when sending fedmsg message: " + e.getMessage());
        }
    }
}
