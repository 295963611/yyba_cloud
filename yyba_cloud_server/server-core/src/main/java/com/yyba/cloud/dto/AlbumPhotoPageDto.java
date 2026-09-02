package com.yyba.cloud.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AlbumPhotoPageDto {
    private List<AlbumPhotoItemDto> items;
    private boolean hasMore;
}
