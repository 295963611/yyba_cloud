package com.yyba.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SetupBrowseResponse {
    private String current;
    private String parent;
    private List<BrowseEntry> entries;
}
