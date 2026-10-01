package com.renhejia.robot.letianpaiservice;

interface ILetianpaiService {
    void setMcuCommand(String command, String data);
    void setSpeechCmd(String command, String data);
    void setTTS(String command, String data);
    void setExpression(String command, String data);
}
