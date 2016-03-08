package com.cray.stash;


import java.util.HashMap;

public class Message {

    private HashMap<String, Object> content;
    private String topic;

    public Message(HashMap<String, Object> content, String topic) {
        this.topic = topic;
        this.content = content;
    }

    public HashMap<String, Object> getMessage() {return content;}

    public String getTopic() {return topic;}
}
