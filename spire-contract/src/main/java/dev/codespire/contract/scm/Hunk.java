package dev.codespire.contract.scm;

import java.util.List;

public record Hunk(int oldStart, int oldLines, int newStart, int newLines, List<DiffLine> lines) {
}
