import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * One-off harness that exercises the same PDFBox 3.0.3 API path as
 * com.docflow.c3.pipeline.TextExtractStep:
 *   Loader.loadPDF(bytes) + new PDFTextStripper().getText(document)
 *
 * For each PDF discovered under <samplesRoot>, write the extracted text to
 * <outRoot>/<relative path with .txt extension>.
 */
public final class PdfboxCheck {

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: PdfboxCheck <samples-root> <out-root>");
            System.exit(2);
        }
        Path samplesRoot = Paths.get(args[0]).toAbsolutePath().normalize();
        Path outRoot = Paths.get(args[1]).toAbsolutePath().normalize();

        List<Path> pdfs;
        try (Stream<Path> stream = Files.walk(samplesRoot)) {
            pdfs = stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                .sorted()
                .toList();
        }

        System.out.println("Found " + pdfs.size() + " PDF(s) under " + samplesRoot);

        int ok = 0;
        int fail = 0;
        for (Path pdf : pdfs) {
            Path rel = samplesRoot.relativize(pdf);
            Path outFile = outRoot.resolve(rel.toString().replaceAll("\\.[pP][dD][fF]$", ".txt"));
            Files.createDirectories(outFile.getParent());

            try {
                byte[] bytes = Files.readAllBytes(pdf);
                try (PDDocument document = Loader.loadPDF(bytes)) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    String text = stripper.getText(document);
                    Files.writeString(outFile, text);
                    System.out.println("OK   " + rel + "  -> " + outFile + "  (" + text.length() + " chars, " + document.getNumberOfPages() + " pages)");
                    ok++;
                }
            } catch (Exception e) {
                String msg = "EXTRACTION FAILED: " + e.getClass().getName() + ": " + e.getMessage() + "\n";
                Files.writeString(outFile, msg);
                System.err.println("FAIL " + rel + "  -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
                fail++;
            }
        }

        System.out.println();
        System.out.println("Summary: ok=" + ok + " fail=" + fail + " total=" + pdfs.size());
    }
}
