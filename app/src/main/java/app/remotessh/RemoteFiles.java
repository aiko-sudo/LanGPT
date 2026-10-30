package app.remotessh;

import java.util.ArrayList;
import java.util.List;

/** Complete, read-only SFTP listing, including regular and hidden files. */
final class RemoteFiles {
  static java.util.Comparator<Entry> projectComparator(int mode) {
    return (a,b) -> {
      int order;
      if (mode >= 2) {
        if ((a.modified > 0) != (b.modified > 0)) return a.modified > 0 ? -1 : 1;
        order = a.modified <= 0 && b.modified <= 0 ? 0
            : mode == 3 ? Long.compare(b.modified, a.modified) : Long.compare(a.modified, b.modified);
      } else {
        order = String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
        if (mode == 1) order = -order;
      }
      if (order != 0) return order;
      order = String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
      return order != 0 ? order : a.name.compareTo(b.name);
    };
  }
  static final class Entry {
    final String name;
    final boolean directory, link;
    final long size, modified;
    final String permissions;
    Entry(String name, boolean directory, boolean link, long size, long modified, String permissions) {
      this.name=name; this.directory=directory; this.link=link;
      this.size=size; this.modified=modified; this.permissions=permissions;
    }
  }
  RemoteFiles sorted(int mode) {
    RemoteFiles copy = new RemoteFiles(path, entries);
    copy.entries.sort((a,b) -> {
      if (a.directory != b.directory) return a.directory ? -1 : 1;
      int order = mode == 1 ? Long.compare(b.modified, a.modified)
          : mode == 2 ? Long.compare(b.size, a.size) : 0;
      if (order != 0) return order;
      order = String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
      return order != 0 ? order : a.name.compareTo(b.name);
    });
    return copy;
  }

  private static final java.util.Set<String> TEXT_EXT=new java.util.HashSet<>(java.util.Arrays.asList(
      "txt","md","markdown","json","geojson","xml","yml","yaml","toml","ini","cfg","conf","properties","env",
      "log","csv","tsv","sql","py","pyi","rb","pl","pm","js","mjs","ts","tsx","jsx","java","kt","kts","c","h",
      "cpp","hpp","cc","cs","go","rs","swift","php","sh","bash","zsh","fish","bat","ps1","html","htm","css",
      "scss","less","r","m","tex","bib","sty","cls","clj","lisp","elm","dart","scala","lua","vim","service",
      "desktop","gitignore","gitattributes","dockerfile","makefile","mk","cmake","gradle","sbt","sum","mod",
      "lock","tf","tfvars","proto","graphql","gql","svg","nix","patch","diff","rst","adoc","asciidoc","org","textile","wiki","tt","text","hcl","j2","jinja","twig","liquid","erb","ejs","hbs","pug","vue","svelte","styl","sass","rmd","ipynb","tcl","awk","sed","bazel","bzl","cmakelists","zig","nim","cr","d","groovy","vb","ml","mli","fs","fsx","pas","asm","sv","v","f","f90","f95","for","rkt","scheme","hx","coffee","ls","es"));
  private static final java.util.Set<String> BINARY_EXT=new java.util.HashSet<>(java.util.Arrays.asList(
      "png","jpg","jpeg","gif","webp","bmp","ico","svgz","pdf","zip","gz","bz2","xz","zst","tar","rar","7z",
      "apk","jar","so","dylib","dll","exe","bin","deb","rpm","iso","doc","docx","xls","xlsx","ppt","pptx",
      "odt","ods","odp","mp3","flac","ogg","wav","m4a","mp4","mkv","avi","mov","webm","wmv","onnx","pt","pth",
      "pkl","db","sqlite","sqlite3","class","pyc","wasm","ttf","otf","woff","woff2","eot","img"));

  /** True when the name looks previewable as text; unknown extensions still get a preview attempt. */
  static boolean isLikelyText(String name) {
    if(name==null||name.isEmpty())return true;
    String lower=name.toLowerCase(java.util.Locale.ROOT);
    for(String plain:new String[]{"makefile","dockerfile","license","readme","contributing","changelog"})
      if(lower.equals(plain))return true;
    int dot=lower.lastIndexOf('.');
    if(dot<0)return true;
    String ext=lower.substring(dot+1);
    if(TEXT_EXT.contains(ext))return true;
    return !BINARY_EXT.contains(ext);
  }

  /** Compact human size: KB below 1 MiB, then MB/GB. */
  static String humanSize(long bytes) {
    java.util.Locale locale=java.util.Locale.ROOT;
    if(bytes>=1024L*1024*1024)return String.format(locale,"%.1f GB",bytes/1073741824.0);
    if(bytes>=1024L*1024)return String.format(locale,"%.1f MB",bytes/1048576.0);
    return String.format(locale,"%.1f KB",bytes/1024.0);
  }

  final String path;
  final List<Entry> entries;
  RemoteFiles(String path, List<Entry> entries) {
    this.path=path; this.entries=new ArrayList<>(entries);
    this.entries.sort((a,b) -> {
      if (a.directory!=b.directory) return a.directory ? -1 : 1;
      int order=String.CASE_INSENSITIVE_ORDER.compare(a.name,b.name);
      return order!=0 ? order : a.name.compareTo(b.name);
    });
  }
}
