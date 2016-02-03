package com.cray.stash;


import java.util.HashMap;

public class Message {

    private HashMap<String, Object> message;
    private String topic;

    public Message(HashMap<String, Object> message, String topic) {
        this.topic = topic;
        this.message = message;
    }

    public HashMap<String, Object> getMessage() {
        return message;
    }

    public String getTopic() {return topic;}
}
