package com.yyba.cloud.service;

import com.yyba.cloud.dto.BrowseEntry;
import com.yyba.cloud.dto.SetupBrowseResponse;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 引导初始化时浏览服务器本地目录。
 */
@Service
public class SetupBrowseService {

    public SetupBrowseResponse browse(String path) {
        if (path == null || path.isBlank()) {
            return listRoots();
        }
        Path dir = Path.of(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("路径不是有效目录: " + path);
        }
        return listDirectory(dir);
    }

    private SetupBrowseResponse listRoots() {
        List<BrowseEntry> entries = new ArrayList<>();
        for (File root : File.listRoots()) {
            String rootPath = root.getAbsolutePath();
            entries.add(new BrowseEntry(rootPath, rootPath));
        }
        entries.sort(Comparator.comparing(BrowseEntry::getName, String.CASE_INSENSITIVE_ORDER));
        return new SetupBrowseResponse("", null, entries);
    }

    private SetupBrowseResponse listDirectory(Path dir) {
        List<BrowseEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(child -> {
                        try {
                            if (!Files.isReadable(child)) {
                                return;
                            }
                            String childPath = child.toAbsolutePath().normalize().toString();
                            entries.add(new BrowseEntry(child.getFileName().toString(), childPath));
                        } catch (Exception ignored) {
                            // skip inaccessible entries
                        }
                    });
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取目录: " + dir);
        }

        Path parent = dir.getParent();
        String parentPath = parent == null ? "" : parent.toAbsolutePath().normalize().toString();
        return new SetupBrowseResponse(dir.toString(), parentPath, entries);
    }
}
