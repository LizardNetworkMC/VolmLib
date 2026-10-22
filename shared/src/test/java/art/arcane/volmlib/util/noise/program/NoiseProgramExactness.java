package art.arcane.volmlib.util.noise.program;

import art.arcane.volmlib.util.noise.CNG;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class NoiseProgramExactness {
    private NoiseProgramExactness() {
    }

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        ProgramParity.Budget budget = new ProgramParity.Budget(
                args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000,
                args.length > 1 ? Integer.parseInt(args[1]) : 1_000_000,
                args.length > 2 ? Integer.parseInt(args[2]) : 4_000,
                args.length > 3 ? Integer.parseInt(args[3]) : 6_000,
                args.length > 4 ? Integer.parseInt(args[4]) : 600);
        int threads = args.length > 5 ? Integer.parseInt(args[5]) : 4;
        List<ProgramShapes.Shape> shapes = ProgramShapes.all();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<String>> rows = new ArrayList<>(shapes.size());
        long seed = 424242L;
        for (ProgramShapes.Shape shape : shapes) {
            long shapeSeed = seed;
            seed += 104729L;
            rows.add(executor.submit(() -> row(shape, shapeSeed, budget)));
        }
        System.out.println("shape | representable | " + String.join(" | ", ProgramParity.APIS) + " | total compared | total mismatches");
        for (Future<String> row : rows) {
            System.out.println(row.get());
        }
        executor.shutdown();
    }

    private static String row(ProgramShapes.Shape shape, long seed, ProgramParity.Budget budget) {
        CNG cng = shape.create(seed);
        boolean representable = NoiseProgram.compile2D(cng).isExactlyRepresentable() && NoiseProgram.compileSigned3D(cng).isExactlyRepresentable();
        ProgramParity.Result result = ProgramParity.check(cng, seed, budget);
        StringBuilder line = new StringBuilder(shape.name()).append(" | ").append(representable ? "yes" : "no");
        for (int api = 0; api < ProgramParity.APIS.length; api++) {
            line.append(" | ").append(result.mismatches[api]).append('/').append(result.compared[api]);
        }
        line.append(" | ").append(result.totalCompared()).append(" | ").append(result.totalMismatches());
        if (result.firstMismatch != null) {
            line.append(" | first ").append(result.firstMismatch);
        }
        return line.toString();
    }
}
