package com.mes.common;

import java.util.List;

public record PageResult(List<?> records, long total, int page, int size) {
}
