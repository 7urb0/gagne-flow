package com.gagneflow.service.reader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface DocumentReader {
    public List<String> getSupportedExtensions();

    public String readText(Path var1) throws IOException;
}
