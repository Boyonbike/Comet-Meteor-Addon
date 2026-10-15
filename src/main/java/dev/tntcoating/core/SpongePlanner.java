package dev.tntcoating.core;
import java.util.*;
import java.util.function.Predicate;
/** Vanilla sponge traversal: six-neighbour connected water, depth six, at most 64 absorbed cells. */
public final class SpongePlanner {
    private SpongePlanner() {}
    public record Cell(int x, int y, int z) {}
    private record Node(Cell cell, int depth) {}
    public static int score(Cell origin, Predicate<Cell> absorbable) {
        Set<Cell> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        visited.add(origin); queue.add(new Node(origin, 0));
        int count = 0;
        while (!queue.isEmpty() && count < 64) {
            Node node = queue.removeFirst();
            if (node.depth == 6) continue;
            Cell p = node.cell;
            Cell[] neighbours = {new Cell(p.x,p.y-1,p.z), new Cell(p.x,p.y+1,p.z),
                new Cell(p.x,p.y,p.z-1),new Cell(p.x,p.y,p.z+1),new Cell(p.x-1,p.y,p.z),new Cell(p.x+1,p.y,p.z)};
            for (Cell next : neighbours) {
                if (!visited.add(next) || !absorbable.test(next)) continue;
                count++;
                if (count == 64) return count;
                queue.addLast(new Node(next,node.depth+1));
            }
        }
        return count;
    }
}
