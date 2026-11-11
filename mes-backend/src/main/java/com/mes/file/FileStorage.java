package com.mes.file;

/**
 * 附件存储抽象：数据库只记 key，本体落在本地目录或对象存储。
 * 读取时按记录里的 storage_type 选择实现，所以切换配置不会影响老文件的访问。
 */
public interface FileStorage {

    /** LOCAL / S3 */
    String type();

    /** 本地存储返回 null，对象存储返回桶名 */
    String bucket();

    void put(String key, byte[] data, String contentType);

    byte[] get(String key);

    void delete(String key);

    /** 写一个探针文件再删掉，用于「测试存储」按钮 */
    void probe();
}
