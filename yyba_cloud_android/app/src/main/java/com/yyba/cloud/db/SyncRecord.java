package com.yyba.cloud.db;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 本机媒体与云端文件的关联记录（手机 Tab 判「已同步」、上传/下载去重的本地桥表）。
 * <p>列表行以 {@link #localUri} 为锚点；内容身份以 {@link #fileHash}（SHA-256）向服务端校验；
 * 上传或校验成功后写入 {@link #remotePath}。</p>
 */
@Entity(tableName = "sync_records",
        indices = {@Index(value = {"localUri", "fileHash"}, unique = true)})
public class SyncRecord {

    /** Room 自增主键 */
    @PrimaryKey(autoGenerate = true)
    public long id;
    /** MediaStore {@code _ID}，与 {@link #localUri} 同源，便于按系统媒体 ID 查询 */
    public Long mediaId;
    /** 本机媒体 content URI（如 {@code content://media/...}），列表/UI 关联的主键 */
    public String localUri;
    /** 可读文件路径（若有），仅辅助展示或调试，非主关联键 */
    public String localPath;
    /** 文件名，展示与无 hash 时兜底匹配（需配合 size、时间） */
    public String fileName;
    /** 文件大小（字节） */
    public long fileSize;
    /** 文件内容 SHA-256，向云端校验是否已备份的唯一码；算过后缓存避免重复读盘 */
    public String fileHash;
    /** 媒体类型：IMAGE / VIDEO / AUDIO 等 */
    public String mediaType;
    /** 本机最后修改时间（epoch ms） */
    public long modifiedAt;
    /** 云端相对路径（如 {@code photos/2024/01/a.jpg}），上传成功或校验命中后写入 */
    public String remotePath;
    /** 云端 files 表主键（若服务端返回），可选 */
    public Long remoteFileId;
    /** 最近一次确认已同步的时间（epoch ms） */
    public long syncedAt;
}
