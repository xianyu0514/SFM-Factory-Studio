import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.stream.Collectors;

/** Run with: java PinyinReleaseSmoke.java <release.jar> <original-PinIn.jar> <fastutil.jar> */
class PinyinReleaseSmoke {
    public static void main(String[] args) throws Exception {
        Path release = Path.of(args[0]), original = Path.of(args[1]), fastutil = Path.of(args[2]);
        // The original PinIn exposes the same packages that caused the jecharacters conflict.
        var finder = ModuleFinder.of(release, original, fastutil);
        var roots = finder.findAll().stream().map(m -> m.descriptor().name()).collect(Collectors.toSet());
        Configuration configuration = ModuleLayer.boot().configuration().resolve(finder, ModuleFinder.of(), roots);
        ModuleLayer.boot().defineModulesWithOneLoader(configuration, ClassLoader.getSystemClassLoader());
        try (var loader = new URLClassLoader(new java.net.URL[]{release.toUri().toURL(), fastutil.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            var type = loader.loadClass("sfmstudio.pinyin.PinIn");
            var instance = type.getConstructor().newInstance();
            var contains = type.getMethod("contains", String.class, String.class);
            if (!Boolean.TRUE.equals(contains.invoke(instance, "石头", "shitou")) ||
                    !Boolean.TRUE.equals(contains.invoke(instance, "石头", "st"))) {
                throw new AssertionError("Relocated pinyin dictionary failed to match");
            }
        }
        System.out.println("PASS: production pinyin classes/dictionary load and match; original and relocated packages coexist in a module layer");
    }
}
