import java.nio.file.Path;

/** Repeatable command-line verification for the minimal UT99 Asset Studio workflow. */
public final class AssetStudioExportTool {
    private AssetStudioExportTool() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Usage: AssetStudioExportTool <UT99-root> <ucc.exe> [animal]");
            System.exit(2);
        }
        boolean animal = args.length == 3 && args[2].equalsIgnoreCase("animal");
        AssetStudioProject project = animal
                ? AssetStudioProject.createLowPolyAnimal() : AssetStudioProject.createDemoCube();
        if (animal) {
            project.packageName = "AssetStudioAnimalDemo";
            project.actorClassName = "AssetStudioAnimalDemoActor";
            Path root = Path.of(args[0]);
            Ut99AssetExporter.addPackageToEditPackages(root, project.packageName);
        }
        Ut99AssetExporter.CompileResult result = Ut99AssetExporter.compile(
                project, Path.of(args[0]), Path.of(args[1]));
        System.out.print(result.output());
        System.out.println("Export source: " + result.export().packageRoot());
        System.out.println("Package file: " + result.packageFile());
        if (!result.success()) {
            throw new IllegalStateException("UCC did not produce a verified .u package (exit code "
                    + result.exitCode() + ").");
        }
    }
}
