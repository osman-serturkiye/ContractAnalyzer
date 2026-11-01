package ai;

import java.util.ArrayList;
import java.util.List;

public final class ChatRequest {
    public String model;
    public String system;
    public String user;
    /** Base64 PNG images (no data: prefix). */
    public List<String> images = new ArrayList<>();
    public boolean jsonMode;
    public Double temperature;
    public Integer maxTokens;
}
