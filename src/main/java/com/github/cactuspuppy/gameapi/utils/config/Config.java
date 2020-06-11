package com.github.cactuspuppy.gameapi.utils.config;

import com.github.cactuspuppy.gameapi.GameAPI;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom YML parser supporting #comments and indented key-value pairs
 *
 * @author CactusPuppy
 */
@SuppressWarnings("WeakerAccess")
@NoArgsConstructor
public class Config implements Map<String, String> {
    /**
     * How many spaces each level should indent
     */
    @Getter @Setter
    private int spacesPerIndent = 2;

    /**
     * Pattern to find key-value pairs
     */
    private static final Pattern KEY_VALUE_MATCHER = Pattern.compile("^( *)([^:\\n ][^:\\n]*):( *)([^:\\n]*)$");

    /**
     * Pattern to capture comments
     */
    private static final Pattern COMMENT_MATCHER = Pattern.compile("([^#\\n]*?)( *# *.*)");

    /**
     * Root node of the config. All nodes should be children of this node.
     */
    private RootNode rootNode = new RootNode();

    /**
     * Flat map of all string keys. Does not include comments.
     */
    private Map<String, String> cache = new HashMap<>();

    /**
     * Loads the specified YAML-style file into the config, dropping all previous keys, values, and comments.
     * @param configFile File to load from
     * @throws IllegalArgumentException      If {@code configFile} is null
     * @throws RuntimeException              If the file is not a valid config file
     * @throws IOException                   If the file cannot be read for any reason
     */
    public void load(File configFile) throws IllegalArgumentException, RuntimeException, IOException {
        if (configFile == null) {
            throw new IllegalArgumentException("Config file must not be null");
        }
        try (FileInputStream fIS = new FileInputStream(configFile)) {
            loadInputStream(fIS);
        }
    }

    /**
     * Loads the specified YAML-style file into the config, dropping all previous keys, values, and comments.
     * @param fileName File to load
     * @throws IllegalArgumentException      If {@code fileName} is null or empty
     * @throws RuntimeException              If the file is not a valid config file
     * @throws IOException                   If the file cannot be read for any reason
     * @throws FileNotFoundException         If the file corresponding to {@code fileName} could not be found
     */
    public void load(String fileName) throws IllegalArgumentException, FileNotFoundException,
    RuntimeException, IOException {
        if (fileName == null || fileName.isEmpty()) {
            throw new IllegalArgumentException("Filename must not be empty or null");
        }
        File configFile = new File(fileName);
        if (!configFile.isFile()) {
            throw new FileNotFoundException(String.format("File %s could not be found", fileName));
        }
        load(configFile);
    }

    /**
     * Loads a config string generated by {@link #saveToString()} or read from a valid YAML-style
     * config file, dropping all previous keys, values, and comments.
     * @param configString String to read from
     * @throws IllegalArgumentException If {@code configString} is null or empty
     * @throws RuntimeException If {@code configString} is not a valid config string
     */
    public void loadFromString(String configString) throws IllegalArgumentException, RuntimeException {
        if (configString == null || configString.isEmpty()) {
            throw new IllegalArgumentException("Filename must not be empty or null");
        }
        loadInputStream(new ByteArrayInputStream(configString.getBytes()));
    }

    private void loadInputStream(InputStream stream) {
        int lineIndex = 0;
        try (Scanner scan = new Scanner(stream)) {
            //Track indent levels
            LinkedList<Integer> currIndents = new LinkedList<>();
            currIndents.add(-1);

            LinkedList<KeyNode> currentParents = new LinkedList<>();
            currentParents.add(rootNode);
            KeyNode previousKeyNode = null;
            Node previousNode = null;
            int prevIndent = 0;

            while (scan.hasNextLine()) {
                Node thisNode;
                int currIndent;
                String line = scan.nextLine();
                lineIndex++;

                //Comment handling
                Matcher matcher = COMMENT_MATCHER.matcher(line);
                String comment = null;
                if (matcher.find()) {
                    line = matcher.group(1);
                    comment = matcher.group(2);
                }

                //Find key-value if possible
                matcher = KEY_VALUE_MATCHER.matcher(line);
                boolean hasComment = comment != null && !comment.isEmpty();
                if (matcher.matches()) { // Key Node
                    String indent = matcher.group(1);
                    String key = matcher.group(2).trim();
                    int colonSpace = matcher.group(3).length();
                    String value = matcher.group(4).trim();
                    currIndent = indent.length();
                    KeyNode thisKeyNode = new KeyNode(key);
                    thisKeyNode.setColonSpace(colonSpace);
                    if (hasComment) {
                        thisKeyNode.setComment(comment);
                    }
                    handleIndent(currIndents, currentParents, previousKeyNode, prevIndent, currIndent);

                    StringJoiner keyMaker = new StringJoiner(".");
                    for (Node n : currentParents) {
                        if (n instanceof KeyNode && !(n instanceof RootNode)) {
                            keyMaker.add(((KeyNode) n).key);
                        }
                    }
                    keyMaker.add(key);
                    currentParents.getLast().getKeyChildren().put(key, thisKeyNode);
                    if (!value.isEmpty()) {
                        thisKeyNode.setValue(value);
                        cache.put(keyMaker.toString(), value);
                    }

                    previousKeyNode = thisKeyNode;
                    thisNode = thisKeyNode;
                } else if (hasComment) { // Comment Node
                    if (line.length() > 0) {
                        throw new RuntimeException(
                            String.format("Invalid sequence on line %d: %s", lineIndex, line)
                        );
                    }
                    currIndent = prevIndent;
                    handleIndent(currIndents, currentParents, previousKeyNode, prevIndent, currIndent);
                    CommentNode commentNode;
                    if (previousNode instanceof CommentNode) {
                        commentNode = (CommentNode) previousNode;
                        commentNode.setComment(commentNode.getComment() + "\n" + comment);
                    } else {
                        commentNode = new CommentNode();
                        commentNode.setComment(comment);
                    }
                    thisNode = commentNode;
                } else if (line.trim().length() == 0) { // Blank line
                    currIndent = line.length();
                    handleIndent(currIndents, currentParents, previousKeyNode, prevIndent, currIndent);
                    BlankNode blankNode;
                    if (previousNode instanceof BlankNode &&
                    ((BlankNode) previousNode).getIndent() == currIndent) {
                        blankNode = (BlankNode) previousNode;
                    } else {
                        blankNode = new BlankNode(0, currIndent);
                    }
                    blankNode.incrLineCount();
                    thisNode = blankNode;
                } else {
                    throw new RuntimeException(
                        String.format("Invalid sequence on line %d: %s", lineIndex, line)
                    );
                }

                if (previousNode == null || !previousNode.equals(thisNode)) {
                    currentParents.getLast().getChildren().add(thisNode);
                }
                prevIndent = currIndent;
                previousNode = thisNode;
            }
        } catch (NoSuchElementException | IllegalStateException e) {
            GameAPI.getPlugin().getLogger().severe("Exception while parsing new config input stream at line " + lineIndex);
            throw new RuntimeException();
        }
    }

    private void handleIndent(LinkedList<Integer> currIndents, LinkedList<KeyNode> currentParents,
                              KeyNode previousKeyNode, int prevIndent, int currIndent) {
        if (currIndent > prevIndent) {
            currentParents.addLast(previousKeyNode);
            currIndents.add(prevIndent);
        } else if (currIndent < prevIndent) {
            while (!currIndents.isEmpty() && currIndent <= currIndents.peekLast()) {
                currIndents.removeLast();
                if (!currentParents.isEmpty()) currentParents.removeLast();
            }
        }
    }

    /**
     * Generates a config string that can be used to reconstruct the current state of the config
     * @return The generated config string
     */
    public String saveToString() {
        StringJoiner configBuilder = new StringJoiner("\n");
        for (Node n : rootNode.getChildren()) {
            buildConfig(n, configBuilder, 0);
        }
        return configBuilder.toString();
    }

    private void buildConfig(Node node, StringJoiner configBuilder, int level) {
        configBuilder.add(node.toString(level));
        if (node instanceof KeyNode) {
            for (Node n : ((KeyNode) node).getChildren()) {
                buildConfig(n, configBuilder, level + 1);
            }
        }
    }

    /**
     * Saves the current config to the specified file, creating the file and any parent directories
     * if it does not exist and overwriting the previous file if it does.
     * @param file File to write to
     * @throws IllegalArgumentException If {@code file} is null
     * @throws IOException              If the {@code file} could not be written to for any reason
     */
    public void save(File file) throws IllegalArgumentException, IOException {
        if (file == null) {
            throw new IllegalArgumentException("Provided file may not be null");
        }
        String config = saveToString();
        FileUtils.writeStringToFile(file, config, (String) null);
    }

    /** The number of key-value pairs currently stored in the config */
    @Override
    public int size() {
        return cache.size();
    }

    /** Returns true if no key-value pairs are stored in the config */
    @Override
    public boolean isEmpty() {
        return cache.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return cache.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return cache.containsValue(value);
    }

    @Override
    public String get(Object key) {
        return cache.get(key);
    }

    @SuppressWarnings("SuspiciousMethodCalls")
    @Override
    public String getOrDefault(Object key, String def) {
        return cache.getOrDefault(key, def);
    }

    @Nullable
    @Override
    public String put(String key, String value) {
        put(key, value, null);
        return null;
    }

    /**
     * Insert a key-value pair with a trailing comment. Key must be not null and not empty,
     * otherwise the method will fail silently and return {@code null}
     * @param key     Key to insert
     * @param value   Value to associate with the key
     * @param comment Comment to append to the end of the line in the config.
     *                May be {@code null} or empty ({@code ""}), in which case no comment will be appended.
     * @return The previous value associated with the key, or null if there was no mapping
     */
    public String put(String key, String value, String comment) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        String[] keyBits = key.split("\\.");
        KeyNode parent = rootNode;
        KeyNode traveller = rootNode;
        for (String bit : keyBits) {
            traveller = traveller.getKeyChildren().get(bit);
            if (traveller == null) {
                traveller = new KeyNode(bit);
                parent.getChildren().add(traveller);
                parent.getKeyChildren().put(bit, traveller);
            }
            parent = traveller;
        }
        String previous = traveller.getValue();
        traveller.setValue(value);
        if (comment != null && !comment.isEmpty()) {
            traveller.setComment(comment);
        }
        cache.put(key, value);
        return previous;
    }

    /**
     * Removes the mapping of this key while maintaining all child mappings
     * @param obj Key to remove
     * @return The value previously mapped to the key, if any.
     */
    @SuppressWarnings("Duplicates")
    @Override
    public String remove(Object obj) {
        if (!(obj instanceof String)) {
            return null;
        }
        String key = (String) obj;
        if (key.isEmpty()) {
            return null;
        }
        String[] keyBits = key.split("\\.");
        KeyNode traveller = rootNode;
        for (String bit : keyBits) {
            traveller = traveller.getKeyChildren().get(bit);
            if (traveller == null) {
                return null;
            }
        }
        String previous = traveller.getValue();
        traveller.setValue(null);
        cache.remove(key);
        return previous;
    }

    /**
     * Removes the specified key along with all child keys.
     * Under most circumstances, {@link Config#remove(Object)} is probably the desired operation.
     * @param obj Key whose mapping should be removed
     * @return The value previously mapped to the key, if any.
     */
    @SuppressWarnings("Duplicates")
    public String kill(Object obj) {
        if (!(obj instanceof String)) {
            return null;
        }
        String key = (String) obj;
        if (key.isEmpty()) {
            return null;
        }
        String[] keyBits = key.split("\\.");
        KeyNode traveller = rootNode;
        KeyNode parent = rootNode;
        for (String bit : keyBits) {
            parent = traveller;
            traveller = traveller.getKeyChildren().get(bit);
            if (traveller == null) {
                return null;
            }
        }
        String previous = traveller.getValue();
        String prefix = (keyBits.length > 1
        ? String.join(".", Arrays.copyOfRange(keyBits, 0, keyBits.length - 1))
        : null);
        killNode(traveller, parent, prefix);
        return previous;
    }

    private void killNode(Node node, KeyNode parent, String prefix) {
        //Remove from parent
        parent.getChildren().remove(node);
        if (node instanceof KeyNode) {
            KeyNode keyNode = (KeyNode) node;
            String fullKey = (prefix == null ? keyNode.key : prefix + "." + keyNode.key);
            parent.getKeyChildren().remove(keyNode.key, node);
            //Remove from cache
            cache.remove(fullKey);
            //Kill any children of this node
            for (Node n : new ArrayList<>(keyNode.getChildren())) {
                killNode(n, keyNode, fullKey);
            }
        }
    }

    /**
     * Add a standalone comment to the end of the config.<br>
     * This comment is automatically prepended by "#";
     * any spaces after the "#" must be manually included in {@code comment}.
     * @param comment Comment to add
     * @param indent Number of levels to indent by
     */
    public void addComment(String comment, int indent) {
        comment = StringUtils.repeat(" ", indent * spacesPerIndent) + "#" + comment;
        CommentNode commentNode = new CommentNode();
        commentNode.setComment(comment);
        rootNode.getChildren().add(commentNode);
    }

    /**
     * {@code indent} defaulted to 0
     *
     * @see #addComment(String, int)
     */
    public void addComment(String comment) {
        addComment(comment, 0);
    }

    /**
     * Add a series of blank lines to the end of the config
     * @param lines Number of blank lines to add
     * @param indent Number of levels to indent these lines by
     */
    public void addBlankLines(int lines, int indent) {
        BlankNode blankNode = new BlankNode(lines, indent);
        rootNode.getChildren().add(blankNode);
    }

    /**
     * {@code indent} defaulted to 0
     *
     * @see #addBlankLines(int, int)
     */
    public void addBlankLines(int lines) {
        addBlankLines(lines, 0);
    }

    @Override
    public void putAll(@NotNull Map<? extends String, ? extends String> m) {
        for (String key : m.keySet()) {
            put(key, m.get(key));
        }
    }

    /**
     * Removes all keys, values, and comments from the config
     */
    @Override
    public void clear() {
        rootNode.getChildren().clear();
        cache.clear();
    }

    /**
     * Get an unmodifiable snapshot of the current cache which throws an {@link UnsupportedOperationException}
     * exception if any mutation is attempted.
     * @return An unmodifiable copy of the cache.
     */
    public Map<String, String> getCache() {
        return Collections.unmodifiableMap(cache);
    }

    @NotNull
    @Override
    public Set<String> keySet() {
        return cache.keySet();
    }

    @NotNull
    @Override
    public Collection<String> values() {
        return cache.values();
    }

    @NotNull
    @Override
    public Set<Entry<String, String>> entrySet() {
        return cache.entrySet();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Map) {
            return cache.equals(obj);
        }
        return false;
    }

    /**
     * Represents one section of the config
     */
    private interface Node {
        String toString(int indentSpaces);
    }

    /**
     * Represents a key-value pair which may be followed by a comment
     */
    @Getter @Setter
    @RequiredArgsConstructor
    private class KeyNode extends CommentNode {
        private final String key;
        private int colonSpace = 1;
        private String value;
        @Setter(AccessLevel.NONE)
        private List<Node> children = new ArrayList<>();
        @Setter(AccessLevel.NONE)
        private final Map<String, KeyNode> keyChildren = new HashMap<>();

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof KeyNode)) {
                return false;
            }
            KeyNode other = (KeyNode) obj;
            return key.equals(other.key) && value.equals(other.value);
        }

        @Override
        public String toString(int indentSpaces) {
            return StringUtils.repeat(" ", indentSpaces * spacesPerIndent)
            + key + ":" + StringUtils.repeat(" ", colonSpace) + value
            + (getComment() != null ? getComment() : "");
        }
    }

    private class RootNode extends KeyNode {
        public RootNode() {
            super(null);
        }
    }

    /**
     * Represents a # prefixed comment
     */
    @Getter @Setter
    private class CommentNode implements Node {
        private String comment = null;

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof CommentNode)) {
                return false;
            }
            return comment.equals(((CommentNode) obj).comment);
        }

        @Override
        public String toString() {
            return null;
        }

        @Override
        public String toString(int indentSpaces) {
            return comment;
        }
    }

    /**
     * Represents one or more blank lines.
     * These lines
     */
    @Getter @Setter
    @AllArgsConstructor
    private class BlankNode implements Node {
        /**
         * Number of blank lines this node accounts for.
         */
        private int lineCount;
        /**
         * Number of spaces to indent these lines by
         */
        private int indent;

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof BlankNode)) {
                return false;
            }
            return lineCount == ((BlankNode) obj).lineCount;
        }

        public void incrLineCount() {
            lineCount++;
        }

        @Override
        public String toString(int indentSpaces) {
            StringJoiner joiner = new StringJoiner("\n");
            for (int i = 0; i < lineCount; i++) {
                joiner.add(StringUtils.repeat(" ", indent));
            }
            return joiner.toString();
        }
    }
}