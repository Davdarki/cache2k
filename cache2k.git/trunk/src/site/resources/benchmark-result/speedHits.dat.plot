set terminal svg
set output 'target/benchmark-result/speedHits.svg'
set boxwidth 0.9 absolute
set style fill solid 1.00 border lt -1
set key outside right top vertical Right noreverse noenhanced autotitles nobox
set style histogram clustered gap 2 title  offset character 0, 0, 0
set datafile missing '-'
set style data histograms
set xtics border in scale 0,0 nomirror rotate by -45  offset character 0, 0, 0 autojustify
set xtics  norangelimit font "1"
set xtics   ()
set ylabel 'runtime in seconds'
set title 'Runtime of 3 million cache hits'
set yrange [ 0.0 : 2.1362 ] noreverse nowriteback
plot 'target/benchmark-result/speedHits.dat' using 2:xtic(1) ti col, '' u 3 ti col, '' u 4 ti col, '' u 5 ti col, '' u 6 ti col, '' u 7 ti col, '' u 8 ti col
