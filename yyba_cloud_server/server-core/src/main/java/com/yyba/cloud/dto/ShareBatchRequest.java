package com.yyba.cloud.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ShareBatchRequest {
    @NotEmpty
    private List<String> paths;
    @NotNull
    @Min(0)
    @Max(1)
    private Integer share;
}
