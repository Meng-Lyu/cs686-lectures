err-small data:

gradle -p dataflow run -Pargs="--pathToInputFile=/Users/haden/Downloads/proj1/data-small.gz --pathToOutputDir=/Users/haden/Downloads/proj1/output-small --threshold=5


small data:
gradle -p dataflow run -Pargs="--pathToInputFile=/Users/haden/Downloads/proj1/data-small.gz --pathToOutputDir=/Users/haden/Downloads/proj1/output-small --threshold=5"

medium data:

gradle -p dataflow run -Pargs="--pathToInputFile=/Users/haden/Downloads/proj1/data-medium.gz --pathToOutputDir=/Users/haden/Downloads/proj1/output-medium --threshold=10"

large data:
gradle -p dataflow run -Pargs="--pathToInputFile=/Users/haden/Downloads/proj1/data-large.gz --pathToOutputDir=/Users/haden/Downloads/proj1/output-large --threshold=50"
