package com.gagneflow.controller;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.ShowCollectionsResponse;
import io.milvus.param.R;
import io.milvus.param.collection.ShowCollectionsParam;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/milvus"})
public class MilvusCheckController {
    @Autowired
    private MilvusServiceClient milvusClient;

    @GetMapping(value={"/health"})
    public ResponseEntity<Map<String, Object>> simpleHealth() {
        HashMap<String, Object> result = new HashMap<String, Object>();
        try {
            R response = this.milvusClient.showCollections(ShowCollectionsParam.newBuilder().build());
            if (response.getStatus() == 0) {
                result.put("message", "ok");
                result.put("collections", ((ShowCollectionsResponse)response.getData()).getCollectionNamesList().toString());
                return ResponseEntity.ok(result);
            }
            result.put("message", response.getMessage());
            return ResponseEntity.status((int)503).body(result);
        }
        catch (Exception e) {
            result.put("error", e.getMessage());
            return ResponseEntity.status((int)503).body(result);
        }
    }
}
