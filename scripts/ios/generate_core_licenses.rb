#!/usr/bin/env ruby

require "json"
require "optparse"

repo_root = File.expand_path("../..", __dir__)
default_manifest = File.join(repo_root, "scripts", "ios", "bundled-core-release-manifest.json")
default_output = File.join(repo_root, "ios", "App", "Resources", "Cores", "CoreLicenses.json")

options = {
  manifest: default_manifest,
  output: default_output,
}

OptionParser.new do |parser|
  parser.banner = "usage: scripts/ios/generate_core_licenses.rb [--manifest path] [--output path]"

  parser.on("--manifest PATH", "Path to bundled-core release manifest JSON") do |value|
    options[:manifest] = File.expand_path(value)
  end

  parser.on("--output PATH", "Path to write CoreLicenses.json") do |value|
    options[:output] = File.expand_path(value)
  end
end.parse!

manifest = JSON.parse(File.read(options[:manifest]))
licenses = manifest.map do |entry|
  {
    "id" => entry.fetch("runtime_id"),
    "binary_path" => entry.fetch("bundle_relative_path"),
    "license_path" => entry.fetch("license_file"),
    "imported_at" => entry.fetch("imported_at"),
  }
end.sort_by { |entry| entry.fetch("id") }

File.write(options[:output], JSON.pretty_generate(licenses) + "\n")
